// Package faults schedules the faults a scenario asks for (FR-S4): disconnects, app restarts and offline stretches,
// delayed, duplicated and reordered location updates, and skewed device clocks. Everything comes from the agent's
// own random source, so a seed gives the same faults.
package faults

import (
	"math/rand/v2"
	"sort"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/scenario"
)

// Kinds of outage.
const (
	// Disconnect loses the WebSocket only; updates are kept and replayed once it is back.
	Disconnect = "disconnect"
	// Restart restarts the app: the socket drops and the app forgets everything but its sign-in, then resyncs.
	Restart = "restart"
	// Offline loses the network: no socket and no HTTPS; ride commands are made offline and sent afterwards.
	Offline = "offline"
)

// restartTakes is how long an app restart keeps the socket down.
const restartTakes = 3 * time.Second

// Outage is one scheduled fault, at an offset into the run.
type Outage struct {
	Kind     string
	At       time.Duration
	Duration time.Duration
}

// Plan is one agent's faults.
type Plan struct {
	Outages []Outage
	// ClockSkew is added to the device time of every update and offline command.
	ClockSkew time.Duration
	f         scenario.Faults
}

// NewPlan draws an agent's outages over the run, as Poisson processes at each kind's rate.
func NewPlan(r *rand.Rand, f scenario.Faults, run time.Duration) *Plan {
	p := &Plan{f: f}
	if f.ClockSkew.Share > 0 && r.Float64() < f.ClockSkew.Share {
		p.ClockSkew = time.Duration((2*r.Float64() - 1) * float64(f.ClockSkew.Max))
	}
	add := func(kind string, perHour float64, duration func() time.Duration) {
		if perHour <= 0 {
			return
		}
		mean := float64(time.Hour) / perHour
		for at := time.Duration(r.ExpFloat64() * mean); at < run; at += time.Duration(r.ExpFloat64() * mean) {
			p.Outages = append(p.Outages, Outage{Kind: kind, At: at, Duration: duration()})
		}
	}
	add(Disconnect, f.Disconnect.RatePerHour, func() time.Duration { return f.Disconnect.Duration.Sample(r) })
	add(Restart, f.Restart.RatePerHour, func() time.Duration { return restartTakes })
	add(Offline, f.OfflineReplay.RatePerHour, func() time.Duration { return f.OfflineReplay.Duration.Sample(r) })
	sort.Slice(p.Outages, func(i, j int) bool { return p.Outages[i].At < p.Outages[j].At })
	return p
}

// Due removes and answers the outages due by the given offset.
func (p *Plan) Due(at time.Duration) []Outage {
	n := sort.Search(len(p.Outages), func(i int) bool { return p.Outages[i].At > at })
	due := p.Outages[:n]
	p.Outages = p.Outages[n:]
	return due
}

// Update is what happens to one location update on its way out.
type Update struct {
	// Delay holds the update back, at most 2.5 s, so it still leaves before the next one (4 s later).
	Delay time.Duration
	// Duplicate sends it again 1.5 s later: the server keeps the first and ignores the copy by its sequence number.
	Duplicate bool
	// Reorder holds it until just after the next update, which the server applies, ignoring this one as stale.
	Reorder bool
}

// MaxDelay keeps a delayed update before the next one and over a second from either, under the server's limit of
// one location a second.
const MaxDelay = 2500 * time.Millisecond

// Next decides the next update's fate.
func (p *Plan) Next(r *rand.Rand) Update {
	var u Update
	if p.f.ReorderUpdates > 0 && r.Float64() < p.f.ReorderUpdates {
		u.Reorder = true
		return u
	}
	if p.f.DelayedUpdates.Share > 0 && r.Float64() < p.f.DelayedUpdates.Share {
		u.Delay = min(max(p.f.DelayedUpdates.Delay.Sample(r), 1100*time.Millisecond), MaxDelay)
	}
	if p.f.DuplicateUpdates > 0 && r.Float64() < p.f.DuplicateUpdates && u.Delay == 0 {
		u.Duplicate = true
	}
	return u
}
