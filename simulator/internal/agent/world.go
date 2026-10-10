// Package agent runs the simulated drivers and riders (LLD §18.3): each a goroutine with a small state machine that
// uses only the public APIs.
package agent

import (
	"context"
	"errors"
	"math/rand/v2"
	"sync"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/api"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/report"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/router"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/scenario"
)

// World is what all agents share.
type World struct {
	Scenario *scenario.Scenario
	Model    *scenario.Model
	Client   *api.Client
	Tokens   *api.TokenCache
	Router   router.Router
	Report   *report.Recorder
	// WSURL replaces the tickets' url when set.
	WSURL string
	// Code is the one-time code the local profile accepts.
	Code string
	// Start is when the run started; agents measure the scenario's offsets from it.
	Start time.Time

	Handover  *Handover
	Positions *Positions
}

// Since is the offset into the run.
func (w *World) Since() time.Duration {
	return time.Since(w.Start)
}

// Rand is an agent's own random source: the run seed, the kind of agent and its number.
func (w *World) Rand(kind uint64, n int) *rand.Rand {
	return rand.New(rand.NewPCG(w.Scenario.Seed, kind<<32|uint64(n)))
}

// Handover passes a ride's PIN from the rider agent to the driver agent, standing in for the talk at the kerb.
type Handover struct {
	mu   sync.Mutex
	pins map[string]string
}

func NewHandover() *Handover {
	return &Handover{pins: map[string]string{}}
}

func (h *Handover) Give(rideID, pin string) {
	h.mu.Lock()
	h.pins[rideID] = pin
	h.mu.Unlock()
}

// Take answers the PIN once the rider has given it.
func (h *Handover) Take(rideID string) (string, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	pin, ok := h.pins[rideID]
	if ok {
		delete(h.pins, rideID)
	}
	return pin, ok
}

// Positions remembers when each driver sent its latest live updates, so a rider receiving one can tell how long it
// took to arrive (NFR-5).
type Positions struct {
	mu   sync.Mutex
	sent map[string]*[positionsKept]sentUpdate
}

const positionsKept = 16

type sentUpdate struct {
	seq int64
	at  time.Time
}

func NewPositions() *Positions {
	return &Positions{sent: map[string]*[positionsKept]sentUpdate{}}
}

func (p *Positions) Sent(driverID string, seq int64, at time.Time) {
	p.mu.Lock()
	ring, ok := p.sent[driverID]
	if !ok {
		ring = &[positionsKept]sentUpdate{}
		p.sent[driverID] = ring
	}
	ring[seq%positionsKept] = sentUpdate{seq, at}
	p.mu.Unlock()
}

// Latency is how long ago the driver sent the update.
func (p *Positions) Latency(driverID string, seq int64, now time.Time) (time.Duration, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	ring, ok := p.sent[driverID]
	if !ok || ring[seq%positionsKept].seq != seq || ring[seq%positionsKept].at.IsZero() {
		return 0, false
	}
	return now.Sub(ring[seq%positionsKept].at), true
}

// Motion follows a route at its speed.
type Motion struct {
	path      geo.Path
	travelled float64
	speedMps  float64
	at        geo.Point
	heading   float64
	moving    bool
}

func NewMotion(at geo.Point) *Motion {
	return &Motion{path: geo.NewPath([]geo.Point{at}), at: at}
}

// maxSpeedMps keeps simulated drivers well under the server's 150 km/h plausibility limit (LLD §9.5).
const maxSpeedMps = 120 / 3.6

// Drive sets off along a route; factor scales its speed.
func (m *Motion) Drive(route router.Route, factor float64) {
	m.path = geo.NewPath(route.Points)
	m.travelled = 0
	m.speedMps = router.DefaultStraight.SpeedMps
	if route.DurationS > 0 && m.path.LengthM() > 0 {
		m.speedMps = m.path.LengthM() / route.DurationS
	}
	m.speedMps = min(m.speedMps*factor, maxSpeedMps)
	m.moving = m.path.LengthM() > 0
}

// Stop stays where the motion is.
func (m *Motion) Stop() {
	m.path = geo.NewPath([]geo.Point{m.at})
	m.moving = false
}

// Advance moves for a while; it answers false once the route's end is reached.
func (m *Motion) Advance(d time.Duration) bool {
	if !m.moving {
		return false
	}
	m.travelled += m.speedMps * d.Seconds()
	m.at, m.heading = m.path.At(m.travelled)
	if m.travelled >= m.path.LengthM() {
		m.moving = false
	}
	return m.moving
}

func (m *Motion) At() geo.Point    { return m.at }
func (m *Motion) Heading() float64 { return m.heading }
func (m *Motion) Moving() bool     { return m.moving }

// Speed is the current speed in metres a second, zero when stopped.
func (m *Motion) Speed() float64 {
	if !m.moving {
		return 0
	}
	return m.speedMps
}

// route asks the router, falling back to a straight line, so an agent always has somewhere to go.
func (w *World) route(ctx context.Context, from, to geo.Point) router.Route {
	route, err := w.Router.Route(ctx, from, to)
	if err != nil {
		w.Report.Count("route_errors", 1)
		route, _ = router.DefaultStraight.Route(ctx, from, to)
	}
	return route
}

// asProblem is errors.As for API problems.
func asProblem(err error, p **api.Problem) bool {
	return errors.As(err, p)
}

// sleep waits, unless the context ends first.
func sleep(ctx context.Context, d time.Duration) bool {
	if d <= 0 {
		return ctx.Err() == nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}
