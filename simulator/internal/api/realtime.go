package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/url"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
	"github.com/gorilla/websocket"
)

// Message is any server message (docs/schemas/websocket/server-messages.v1.json), flattened: Type says which fields
// are set.
type Message struct {
	Type string `json:"type"`
	// offer, offer_withdrawn
	OfferID         string     `json:"offer_id"`
	RideID          string     `json:"ride_id"`
	Pickup          *geo.Point `json:"pickup"`
	Dropoff         *geo.Point `json:"dropoff"`
	PickupDistanceM int        `json:"pickup_distance_m"`
	ExpiresInMs     int64      `json:"expires_in_ms"`
	Reason          string     `json:"reason"`
	// driver_status, ride_status
	Status  string `json:"status"`
	Version int64  `json:"version"`
	// driver_position
	Lat float64 `json:"lat"`
	Lon float64 `json:"lon"`
	Seq int64   `json:"seq"`
	// reconnect
	AfterMs int64 `json:"after_ms"`
	// error
	Code string `json:"code"`
}

// ErrNotConnected is a send while the socket is down; live locations are then kept for a replay.
var ErrNotConnected = errors.New("websocket not connected")

// Realtime is one user's WebSocket: Run keeps it connected until its context ends.
type Realtime struct {
	s *Session
	// URL replaces the ticket's url when set, for clients that reach the server by another name (Compose).
	URL string
	// OnConnect runs after every connect, beside the reader, to resync over HTTPS (ADR-006).
	OnConnect func(ctx context.Context)
	OnMessage func(Message)

	mu         sync.Mutex
	conn       *websocket.Conn
	downUntil  time.Time
	writeMu    sync.Mutex
	Connects   atomic.Int64
	Reconnects atomic.Int64
}

func (s *Session) Realtime(wsURL string, onConnect func(context.Context), onMessage func(Message)) *Realtime {
	return &Realtime{s: s, URL: wsURL, OnConnect: onConnect, OnMessage: onMessage}
}

var dialer = websocket.Dialer{HandshakeTimeout: 10 * time.Second}

// Run connects, reads and reconnects with backoff and jitter, until ctx ends.
func (r *Realtime) Run(ctx context.Context) {
	backoff := time.Second
	for ctx.Err() == nil {
		r.mu.Lock()
		down := time.Until(r.downUntil)
		r.mu.Unlock()
		if sleep(ctx, down) != nil {
			return
		}
		conn, err := r.dial(ctx)
		if err != nil {
			r.s.c.Stats.Count("ws.connect_failed", 1)
			if sleep(ctx, jitter(backoff)) != nil {
				return
			}
			backoff = min(backoff*2, 30*time.Second)
			continue
		}
		backoff = time.Second
		if r.Connects.Add(1) > 1 {
			r.Reconnects.Add(1)
		}
		r.read(ctx, conn)
	}
}

func (r *Realtime) dial(ctx context.Context) (*websocket.Conn, error) {
	ticket, err := r.s.Ticket(ctx)
	if err != nil {
		return nil, err
	}
	target := ticket.URL
	if r.URL != "" {
		target = r.URL
	}
	u, err := url.Parse(target)
	if err != nil {
		return nil, err
	}
	q := u.Query()
	q.Set("ticket", ticket.Ticket)
	u.RawQuery = q.Encode()
	conn, _, err := dialer.DialContext(ctx, u.String(), nil)
	return conn, err
}

func (r *Realtime) read(ctx context.Context, conn *websocket.Conn) {
	r.mu.Lock()
	r.conn = conn
	r.mu.Unlock()
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			conn.Close()
		case <-done:
		}
	}()
	if r.OnConnect != nil {
		go r.OnConnect(ctx)
	}
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			break
		}
		var m Message
		if json.Unmarshal(data, &m) != nil {
			r.s.c.Stats.Count("ws.unreadable", 1)
			continue
		}
		r.s.c.Stats.Count("ws.in."+m.Type, 1)
		switch m.Type {
		case "reconnect":
			// The node is draining (NFR-12): leave after the given delay, which spreads clients over the drain.
			r.drop(time.Now().Add(time.Duration(m.AfterMs)*time.Millisecond), conn, false)
		case "error":
			r.s.c.Stats.Count("ws.error."+m.Code, 1)
		}
		if r.OnMessage != nil {
			r.OnMessage(m)
		}
	}
	r.mu.Lock()
	if r.conn == conn {
		r.conn = nil
	}
	r.mu.Unlock()
	conn.Close()
}

// Send writes a client message; ErrNotConnected while the socket is down.
func (r *Realtime) Send(v any) error {
	r.mu.Lock()
	conn := r.conn
	r.mu.Unlock()
	if conn == nil {
		return ErrNotConnected
	}
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	r.writeMu.Lock()
	defer r.writeMu.Unlock()
	conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if err := conn.WriteMessage(websocket.TextMessage, data); err != nil {
		conn.Close()
		return ErrNotConnected
	}
	return nil
}

// Connected says whether the socket is up.
func (r *Realtime) Connected() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.conn != nil
}

// Disconnect drops the socket at once and keeps it down until the given time, as a lost network would (FR-S4).
func (r *Realtime) Disconnect(until time.Time) {
	r.mu.Lock()
	conn := r.conn
	r.mu.Unlock()
	r.drop(until, conn, true)
}

func (r *Realtime) drop(until time.Time, conn *websocket.Conn, now bool) {
	r.mu.Lock()
	if until.After(r.downUntil) {
		r.downUntil = until
	}
	r.mu.Unlock()
	if conn == nil {
		return
	}
	if now {
		conn.Close()
		return
	}
	time.AfterFunc(time.Until(until), func() { conn.Close() })
}
