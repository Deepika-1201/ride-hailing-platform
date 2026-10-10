// Package api is the simulator's client of the public REST and WebSocket APIs (ADR-021). It follows the rules a real
// app follows (HLD §12.2): commands carry an Idempotency-Key and are retried with the same key; access tokens are
// refreshed before they expire; the WebSocket reconnects with backoff and resyncs over HTTPS.
package api

import (
	"bytes"
	"context"
	crand "crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Problem is an RFC 9457 error answer.
type Problem struct {
	Status       int        `json:"status"`
	Code         string     `json:"code"`
	Title        string     `json:"title"`
	Detail       string     `json:"detail"`
	AttemptsLeft *int       `json:"attempts_left,omitempty"`
	AvailableAt  *time.Time `json:"available_at,omitempty"`
}

func (p *Problem) Error() string {
	return fmt.Sprintf("%d %s: %s", p.Status, p.Code, p.Detail)
}

// CodeOf is the problem code of an error answer, or "" for any other error.
func CodeOf(err error) string {
	var p *Problem
	if errors.As(err, &p) {
		return p.Code
	}
	return ""
}

// Client holds what agents share: the base URL, connections and counters.
type Client struct {
	base  string
	http  *http.Client
	Stats *Stats
	// MaxAttempts bounds retries of one call; Backoff is the first retry's delay, doubled each time.
	MaxAttempts int
	Backoff     time.Duration
}

func NewClient(base string) *Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.MaxIdleConns = 4096
	transport.MaxIdleConnsPerHost = 4096
	return &Client{
		base:        strings.TrimRight(base, "/"),
		http:        &http.Client{Transport: transport, Timeout: 15 * time.Second},
		Stats:       NewStats(),
		MaxAttempts: 6,
		Backoff:     250 * time.Millisecond,
	}
}

// NewKey is an idempotency key: random, so a rerun within a day isn't answered from the last run's stored responses.
func NewKey() string {
	var b [16]byte
	if _, err := crand.Read(b[:]); err != nil {
		panic(err)
	}
	return fmt.Sprintf("sim-%x", b)
}

// call sends one request with retries. token gives the access token ("" for public endpoints) and is asked again
// with refresh=true after a 401. A 2xx answer is decoded into out when out isn't nil; the status is returned.
func (c *Client) call(ctx context.Context, method, path, key string, body, out any,
	token func(ctx context.Context, refresh bool) (string, error)) (int, error) {
	var payload []byte
	if body != nil {
		var err error
		if payload, err = json.Marshal(body); err != nil {
			return 0, err
		}
	}
	refreshed := false
	delay := c.Backoff
	var last error
	for attempt := 1; attempt <= c.MaxAttempts; attempt++ {
		if attempt > 1 {
			c.Stats.Count("retries", 1)
			if err := sleep(ctx, jitter(delay)); err != nil {
				return 0, err
			}
			delay = min(delay*2, 8*time.Second)
		}
		req, err := http.NewRequestWithContext(ctx, method, c.base+path, bytes.NewReader(payload))
		if err != nil {
			return 0, err
		}
		if payload != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if key != "" {
			req.Header.Set("Idempotency-Key", key)
		}
		if token != nil {
			t, err := token(ctx, false)
			if err != nil {
				return 0, err
			}
			req.Header.Set("Authorization", "Bearer "+t)
		}
		started := time.Now()
		resp, err := c.http.Do(req)
		if err != nil {
			if ctx.Err() != nil {
				return 0, ctx.Err()
			}
			c.Stats.Count("http.network_error", 1)
			last = err
			continue
		}
		data, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		c.Stats.Latency(method+" "+template(path), time.Since(started))
		if err != nil {
			last = err
			continue
		}
		c.Stats.Count(fmt.Sprintf("http.%d", resp.StatusCode), 1)
		if resp.StatusCode < 300 {
			if out != nil && len(data) > 0 {
				if err := json.Unmarshal(data, out); err != nil {
					return resp.StatusCode, fmt.Errorf("%s %s: %w", method, path, err)
				}
			}
			return resp.StatusCode, nil
		}
		problem := &Problem{Status: resp.StatusCode}
		_ = json.Unmarshal(data, problem)
		last = problem
		switch {
		case resp.StatusCode == http.StatusUnauthorized && token != nil && !refreshed:
			refreshed = true
			if _, err := token(ctx, true); err != nil {
				return resp.StatusCode, err
			}
			delay = 0
		case resp.StatusCode == http.StatusTooManyRequests, resp.StatusCode >= 500,
			problem.Code == "IDEMPOTENCY_KEY_IN_PROGRESS":
			if after := retryAfter(resp.Header); after > 0 {
				delay = after
			}
		default:
			return resp.StatusCode, problem
		}
	}
	return 0, fmt.Errorf("%s %s: gave up after %d attempts: %w", method, path, c.MaxAttempts, last)
}

func retryAfter(h http.Header) time.Duration {
	seconds, err := strconv.Atoi(h.Get("Retry-After"))
	if err != nil || seconds <= 0 {
		return 0
	}
	return time.Duration(min(seconds, 30)) * time.Second
}

// template replaces IDs in a path, so latencies are kept per endpoint.
func template(path string) string {
	parts := strings.Split(strings.SplitN(path, "?", 2)[0], "/")
	for i, part := range parts {
		if len(part) == 36 && strings.Count(part, "-") == 4 {
			parts[i] = "{id}"
		}
	}
	return strings.Join(parts, "/")
}

func jitter(d time.Duration) time.Duration {
	if d <= 0 {
		return 0
	}
	return d/2 + time.Duration(rand.Int64N(int64(d)))
}

func sleep(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return ctx.Err()
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// Stats counts what happened on the wire, for the report.
type Stats struct {
	mu        sync.Mutex
	counts    map[string]int64
	latencies map[string][]float64
}

func NewStats() *Stats {
	return &Stats{counts: map[string]int64{}, latencies: map[string][]float64{}}
}

func (s *Stats) Count(name string, n int64) {
	s.mu.Lock()
	s.counts[name] += n
	s.mu.Unlock()
}

func (s *Stats) Latency(endpoint string, d time.Duration) {
	s.mu.Lock()
	s.latencies[endpoint] = append(s.latencies[endpoint], float64(d.Microseconds())/1000)
	s.mu.Unlock()
}

// Snapshot copies the counters and latencies in milliseconds.
func (s *Stats) Snapshot() (map[string]int64, map[string][]float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	counts := make(map[string]int64, len(s.counts))
	for k, v := range s.counts {
		counts[k] = v
	}
	latencies := make(map[string][]float64, len(s.latencies))
	for k, v := range s.latencies {
		latencies[k] = append([]float64(nil), v...)
	}
	return counts, latencies
}
