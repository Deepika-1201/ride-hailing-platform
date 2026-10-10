package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// fakeServer answers sign-in and lets each test add handlers.
type fakeServer struct {
	*httptest.Server
	mux       *http.ServeMux
	mu        sync.Mutex
	codes     int
	refreshes []string
	issued    atomic.Int64
	// expiresIn is the first access token's lifetime in seconds.
	expiresIn int
}

func newFakeServer(t *testing.T) *fakeServer {
	f := &fakeServer{mux: http.NewServeMux(), expiresIn: 900}
	f.mux.HandleFunc("POST /v1/auth/otp", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.codes++
		f.mu.Unlock()
		w.WriteHeader(http.StatusAccepted)
		w.Write([]byte(`{"expires_at": "2026-10-10T10:00:00Z", "resend_after_s": 30}`))
	})
	f.mux.HandleFunc("POST /v1/auth/token", func(w http.ResponseWriter, r *http.Request) {
		f.tokens(w, f.expiresIn)
	})
	f.mux.HandleFunc("POST /v1/auth/refresh", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]string
		json.NewDecoder(r.Body).Decode(&body)
		f.mu.Lock()
		f.refreshes = append(f.refreshes, body["refresh_token"])
		f.mu.Unlock()
		if !strings.HasPrefix(body["refresh_token"], "refresh-") {
			problem(w, http.StatusUnauthorized, "INVALID_TOKEN")
			return
		}
		f.tokens(w, 900)
	})
	f.Server = httptest.NewServer(f.mux)
	t.Cleanup(f.Close)
	return f
}

func (f *fakeServer) tokens(w http.ResponseWriter, expiresIn int) {
	n := f.issued.Add(1)
	json.NewEncoder(w).Encode(map[string]any{
		"access_token": "access-" + string(rune('0'+n)), "token_type": "Bearer", "expires_in": expiresIn,
		"refresh_token": "refresh-" + string(rune('0'+n)) + strings.Repeat("x", 20), "refresh_expires_in": 2592000,
		"user": map[string]any{"id": "0199a3f0-0001-7000-8000-000000000001", "roles": []string{"DRIVER"}},
	})
}

func problem(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]any{"status": status, "code": code, "title": code, "request_id": "r"})
}

func fastClient(url string) *Client {
	c := NewClient(url)
	c.Backoff = time.Millisecond
	return c
}

func signIn(t *testing.T, c *Client) *Session {
	s, err := c.SignIn(context.Background(), "+917000000001", "123456", &TokenCache{tokens: map[string]string{}})
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestACommandIsRetriedWithItsIdempotencyKeyUntilItSucceeds(t *testing.T) {
	f := newFakeServer(t)
	var keys []string
	f.mux.HandleFunc("POST /v1/offers/{id}/accept", func(w http.ResponseWriter, r *http.Request) {
		keys = append(keys, r.Header.Get("Idempotency-Key"))
		switch len(keys) {
		case 1:
			problem(w, http.StatusServiceUnavailable, "SERVICE_UNAVAILABLE")
		case 2:
			problem(w, http.StatusConflict, "IDEMPOTENCY_KEY_IN_PROGRESS")
		default:
			w.Write([]byte(`{"id": "ride-1", "status": "DRIVER_ASSIGNED", "version": 1}`))
		}
	})
	s := signIn(t, fastClient(f.URL))

	ride, err := s.Accept(context.Background(), "0199a3f0-8a11-7e22-9f33-a44b55c66d77", "key-1")
	if err != nil {
		t.Fatal(err)
	}
	if ride.Status != "DRIVER_ASSIGNED" || len(keys) != 3 || keys[0] != "key-1" || keys[1] != "key-1" ||
		keys[2] != "key-1" {
		t.Fatalf("ride %+v after keys %v", ride, keys)
	}
}

func TestAnAnswerThatWontChangeIsntRetried(t *testing.T) {
	f := newFakeServer(t)
	calls := 0
	f.mux.HandleFunc("POST /v1/offers/{id}/accept", func(w http.ResponseWriter, r *http.Request) {
		calls++
		problem(w, http.StatusConflict, "OFFER_NO_LONGER_AVAILABLE")
	})
	s := signIn(t, fastClient(f.URL))

	_, err := s.Accept(context.Background(), "0199a3f0-8a11-7e22-9f33-a44b55c66d77", "key-1")
	if CodeOf(err) != "OFFER_NO_LONGER_AVAILABLE" || calls != 1 {
		t.Fatalf("err %v after %d calls", err, calls)
	}
}

func TestA401RotatesTheRefreshTokenOnceAndRetries(t *testing.T) {
	f := newFakeServer(t)
	var seen []string
	f.mux.HandleFunc("GET /v1/drivers/me", func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.Header.Get("Authorization"))
		if len(seen) == 1 {
			problem(w, http.StatusUnauthorized, "TOKEN_EXPIRED")
			return
		}
		w.Write([]byte(`{"status": {"status": "OFFLINE", "version": 0}}`))
	})
	s := signIn(t, fastClient(f.URL))

	status, err := s.DriverStatus(context.Background())
	if err != nil || status.Status != "OFFLINE" {
		t.Fatalf("status %+v, err %v", status, err)
	}
	if len(seen) != 2 || seen[0] != "Bearer access-1" || seen[1] != "Bearer access-2" {
		t.Fatalf("authorizations %v", seen)
	}
}

func TestATokenAboutToExpireIsRefreshedBeforeTheCall(t *testing.T) {
	f := newFakeServer(t)
	f.expiresIn = 30
	var seen string
	f.mux.HandleFunc("GET /v1/drivers/me", func(w http.ResponseWriter, r *http.Request) {
		seen = r.Header.Get("Authorization")
		w.Write([]byte(`{"status": {"status": "OFFLINE", "version": 0}}`))
	})
	s := signIn(t, fastClient(f.URL))

	if _, err := s.DriverStatus(context.Background()); err != nil {
		t.Fatal(err)
	}
	if seen != "Bearer access-2" || len(f.refreshes) != 1 {
		t.Fatalf("called with %q after refreshes %v", seen, f.refreshes)
	}
}

func TestARerunResumesTheCachedSessionWithoutACode(t *testing.T) {
	f := newFakeServer(t)
	path := filepath.Join(t.TempDir(), "tokens.json")
	cache, _ := LoadTokenCache(path)
	c := fastClient(f.URL)
	if _, err := c.SignIn(context.Background(), "+917000000001", "123456", cache); err != nil {
		t.Fatal(err)
	}
	if err := cache.Save(); err != nil {
		t.Fatal(err)
	}

	again, _ := LoadTokenCache(path)
	s, err := c.SignIn(context.Background(), "+917000000001", "123456", again)
	if err != nil {
		t.Fatal(err)
	}
	if f.codes != 1 || len(f.refreshes) != 1 || s.UserID == "" {
		t.Fatalf("codes %d, refreshes %v", f.codes, f.refreshes)
	}
	if token, _ := again.Get("+917000000001"); token == f.refreshes[0] {
		t.Fatal("the cache keeps the rotated token, not the spent one")
	}
}

func TestARevokedCachedSessionFallsBackToACode(t *testing.T) {
	f := newFakeServer(t)
	cache := &TokenCache{tokens: map[string]string{"+917000000001": "revoked-token-xxxxxxxxxxxx"}}

	if _, err := fastClient(f.URL).SignIn(context.Background(), "+917000000001", "123456", cache); err != nil {
		t.Fatal(err)
	}
	if f.codes != 1 {
		t.Fatalf("codes %d", f.codes)
	}
}

func TestTheSocketReconnectsWithANewTicketAndResyncsEachTime(t *testing.T) {
	f := newFakeServer(t)
	var tickets atomic.Int64
	f.mux.HandleFunc("POST /v1/realtime/tickets", func(w http.ResponseWriter, r *http.Request) {
		n := tickets.Add(1)
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]string{"ticket": "t" + string(rune('0'+n)), "url": "ws://wrong.invalid/ws"})
	})
	upgrader := websocket.Upgrader{}
	var used []string
	var mu sync.Mutex
	f.mux.HandleFunc("GET /ws", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		used = append(used, r.URL.Query().Get("ticket"))
		first := len(used) == 1
		mu.Unlock()
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		if first {
			conn.WriteMessage(websocket.TextMessage, []byte(`{"type": "reconnect", "after_ms": 10}`))
			return
		}
		conn.WriteMessage(websocket.TextMessage, []byte(`{"type": "offer_withdrawn", "offer_id": "o1", "reason": "EXPIRED"}`))
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	})
	s := signIn(t, fastClient(f.URL))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	resyncs := make(chan struct{}, 10)
	withdrawn := make(chan Message, 1)
	rt := s.Realtime("ws"+strings.TrimPrefix(f.URL, "http")+"/ws", func(context.Context) { resyncs <- struct{}{} },
		func(m Message) {
			if m.Type == "offer_withdrawn" {
				withdrawn <- m
			}
		})
	go rt.Run(ctx)

	select {
	case m := <-withdrawn:
		if m.OfferID != "o1" || m.Reason != "EXPIRED" {
			t.Fatalf("message %+v", m)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("no message after the reconnect")
	}
	for i := 0; i < 2; i++ {
		select {
		case <-resyncs:
		case <-time.After(10 * time.Second):
			t.Fatalf("%d resyncs", i)
		}
	}
	if rt.Reconnects.Load() != 1 {
		t.Fatalf("reconnects %d", rt.Reconnects.Load())
	}
	mu.Lock()
	defer mu.Unlock()
	if len(used) != 2 || used[0] == used[1] {
		t.Fatalf("tickets used %v", used)
	}
	if err := rt.Send(map[string]string{"type": "offer_seen", "offer_id": "o1"}); err != nil {
		t.Fatal(err)
	}
}

func TestPathsAreGroupedByEndpoint(t *testing.T) {
	if got := template("/v1/rides/0199a3f0-7c2e-7a41-9b3d-5f2e8c1d4a10/start"); got != "/v1/rides/{id}/start" {
		t.Fatal(got)
	}
}
