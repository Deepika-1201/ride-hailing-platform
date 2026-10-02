// Command bench runs the throwaway design spikes: S-1 (live location index),
// S-2 (database timers) and the load client for S-3 (WebSocket connections).
// Not product code.
package main

import (
	"fmt"
	"os"
	"sort"
	"sync"
	"time"
)

func main() {
	commands := map[string]func([]string) error{
		"geo":      runGeo,
		"timers":   runTimers,
		"wsclient": runWSClient,
	}
	if len(os.Args) < 2 || commands[os.Args[1]] == nil {
		fmt.Fprintln(os.Stderr, "usage: bench geo|timers|wsclient [flags]")
		os.Exit(2)
	}
	if err := commands[os.Args[1]](os.Args[2:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

// recorder collects latency samples from many goroutines.
type recorder struct {
	mu      sync.Mutex
	samples []time.Duration
	errs    int
}

func (r *recorder) add(d time.Duration) {
	r.mu.Lock()
	r.samples = append(r.samples, d)
	r.mu.Unlock()
}

func (r *recorder) fail() {
	r.mu.Lock()
	r.errs++
	r.mu.Unlock()
}

func (r *recorder) reset() {
	r.mu.Lock()
	r.samples = r.samples[:0]
	r.errs = 0
	r.mu.Unlock()
}

func (r *recorder) summary(name string, elapsed time.Duration) string {
	r.mu.Lock()
	s := append([]time.Duration(nil), r.samples...)
	errs := r.errs
	r.mu.Unlock()
	if len(s) == 0 {
		return fmt.Sprintf("%-14s n=0 errors=%d", name, errs)
	}
	sort.Slice(s, func(i, j int) bool { return s[i] < s[j] })
	pct := func(q float64) time.Duration { return s[int(q*float64(len(s)-1))].Round(time.Microsecond) }
	return fmt.Sprintf("%-14s n=%-8d rate=%7.0f/s  p50=%-9v p95=%-9v p99=%-9v max=%-10v errors=%d",
		name, len(s), float64(len(s))/elapsed.Seconds(), pct(0.50), pct(0.95), pct(0.99),
		s[len(s)-1].Round(time.Microsecond), errs)
}

// pacer blocks until the next slot of a fixed-rate schedule. When the target
// can't keep up it drops missed slots instead of bursting, so a shortfall shows
// up as a lower achieved rate.
func pacer(rate float64) func() {
	interval := time.Duration(float64(time.Second) / rate)
	next := time.Now()
	return func() {
		next = next.Add(interval)
		if behind := time.Since(next); behind > time.Second {
			next = time.Now()
		}
		if wait := time.Until(next); wait > 0 {
			time.Sleep(wait)
		}
	}
}
