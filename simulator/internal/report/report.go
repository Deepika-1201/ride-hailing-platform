// Package report collects what agents observe and writes a run's report (FR-S5): percentiles, rates, counts, the
// invariant check and the scenario's expectations.
package report

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/api"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/scenario"
)

// Ride outcomes, counted once per booking.
const (
	Completed         = "completed"
	CancelledSearch   = "rider_cancelled_searching"
	CancelledAssigned = "rider_cancelled_assigned"
	NoShow            = "no_show"
	NotFound          = "driver_not_found"
	CancelledSystem   = "cancelled_by_system"
	Unfinished        = "unfinished"
)

// Distributions the report gives percentiles of.
const (
	WaitToAssign    = "wait_to_assign_s"
	WaitToPickup    = "wait_to_pickup_s"
	PickupDistance  = "pickup_distance_m"
	PositionLatency = "position_latency_ms"
)

// Recorder is shared by all agents.
type Recorder struct {
	mu       sync.Mutex
	counts   map[string]int64
	samples  map[string][]float64
	outcomes map[string]string
	offers   map[string]int
}

func NewRecorder() *Recorder {
	return &Recorder{counts: map[string]int64{}, samples: map[string][]float64{}, outcomes: map[string]string{},
		offers: map[string]int{}}
}

func (r *Recorder) Count(name string, n int64) {
	r.mu.Lock()
	r.counts[name] += n
	r.mu.Unlock()
}

func (r *Recorder) Observe(metric string, value float64) {
	r.mu.Lock()
	r.samples[metric] = append(r.samples[metric], value)
	r.mu.Unlock()
}

// Booked starts counting a ride.
func (r *Recorder) Booked(rideID string) {
	r.mu.Lock()
	if _, ok := r.outcomes[rideID]; !ok {
		r.outcomes[rideID] = Unfinished
	}
	r.mu.Unlock()
}

// Ended records a booked ride's outcome; the first outcome stands.
func (r *Recorder) Ended(rideID, outcome string) {
	r.mu.Lock()
	if current, ok := r.outcomes[rideID]; !ok || current == Unfinished {
		r.outcomes[rideID] = outcome
	}
	r.mu.Unlock()
}

// Offered counts an offer a driver received for a ride.
func (r *Recorder) Offered(rideID string) {
	r.mu.Lock()
	r.offers[rideID]++
	r.mu.Unlock()
}

// Outcome maps a ride's final state to an outcome.
func Outcome(ride api.Ride) string {
	switch ride.Status {
	case "COMPLETED":
		return Completed
	case "CANCELLED_BY_RIDER":
		if ride.AssignedAt != nil {
			return CancelledAssigned
		}
		return CancelledSearch
	case "CANCELLED_BY_DRIVER":
		return NoShow
	case "DRIVER_NOT_FOUND":
		return NotFound
	case "CANCELLED_BY_SYSTEM":
		return CancelledSystem
	}
	return Unfinished
}

// Percentiles summarise a distribution.
type Percentiles struct {
	Count int     `json:"count"`
	Mean  float64 `json:"mean"`
	P50   float64 `json:"p50"`
	P90   float64 `json:"p90"`
	P95   float64 `json:"p95"`
	P99   float64 `json:"p99"`
	Max   float64 `json:"max"`
}

// Summarise uses nearest-rank percentiles.
func Summarise(values []float64) Percentiles {
	if len(values) == 0 {
		return Percentiles{}
	}
	sorted := append([]float64(nil), values...)
	sort.Float64s(sorted)
	rank := func(p float64) float64 {
		return sorted[max(0, int(math.Ceil(p*float64(len(sorted))))-1)]
	}
	sum := 0.0
	for _, v := range sorted {
		sum += v
	}
	return Percentiles{Count: len(sorted), Mean: sum / float64(len(sorted)), P50: rank(0.5), P90: rank(0.9),
		P95: rank(0.95), P99: rank(0.99), Max: sorted[len(sorted)-1]}
}

// Check is one expectation and how the run met it.
type Check struct {
	Metric string   `json:"metric"`
	Min    *float64 `json:"min,omitempty"`
	Max    *float64 `json:"max,omitempty"`
	Value  *float64 `json:"value,omitempty"`
	Met    bool     `json:"met"`
}

// Report is a run's result.
type Report struct {
	Scenario      string                 `json:"scenario"`
	Seed          uint64                 `json:"seed"`
	StartedAt     time.Time              `json:"started_at"`
	EndedAt       time.Time              `json:"ended_at"`
	Metrics       map[string]float64     `json:"metrics"`
	Distributions map[string]Percentiles `json:"distributions"`
	Counts        map[string]int64       `json:"counts"`
	Endpoints     map[string]Percentiles `json:"endpoint_latency_ms"`
	Invariants    *api.InvariantReport   `json:"invariants,omitempty"`
	Checks        []Check                `json:"checks"`
	Problems      []string               `json:"problems,omitempty"`
}

// Build turns what was recorded into a report and checks the expectations; invariants may be nil if the check
// couldn't run, which is a problem in itself.
func (r *Recorder) Build(s *scenario.Scenario, started, ended time.Time, wire *api.Stats,
	invariants *api.InvariantReport, invariantErr error) *Report {
	r.mu.Lock()
	defer r.mu.Unlock()
	rep := &Report{Scenario: s.Name, Seed: s.Seed, StartedAt: started, EndedAt: ended,
		Metrics: map[string]float64{}, Distributions: map[string]Percentiles{}, Counts: map[string]int64{},
		Endpoints: map[string]Percentiles{}, Invariants: invariants}
	for name, n := range r.counts {
		rep.Counts[name] = n
	}
	counts, latencies := wire.Snapshot()
	for name, n := range counts {
		rep.Counts[name] += n
	}
	for endpoint, values := range latencies {
		rep.Endpoints[endpoint] = Summarise(values)
	}

	outcomes := map[string]float64{}
	for _, outcome := range r.outcomes {
		outcomes[outcome]++
	}
	booked := float64(len(r.outcomes))
	offers := 0
	for _, n := range r.offers {
		offers += n
	}
	rep.Metrics["rides.booked"] = booked
	for _, o := range []string{Completed, CancelledSearch, CancelledAssigned, NoShow, NotFound, CancelledSystem,
		Unfinished} {
		rep.Metrics["rides."+o] = outcomes[o]
	}
	rep.Metrics["rides.assigned"] = float64(r.counts["rides.assigned"])
	rep.Metrics["offers"] = float64(offers)
	if booked > 0 {
		rep.Metrics["rates.match"] = float64(r.counts["rides.assigned"]) / booked
		rep.Metrics["rates.completion"] = outcomes[Completed] / booked
		rep.Metrics["rates.cancel"] = (outcomes[CancelledSearch] + outcomes[CancelledAssigned] + outcomes[NoShow]) /
			booked
		rep.Metrics["rates.not_found"] = outcomes[NotFound] / booked
		rep.Metrics["offers_per_ride"] = float64(offers) / booked
	}
	for metric, values := range r.samples {
		p := Summarise(values)
		rep.Distributions[metric] = p
		rep.Metrics[metric+".p50"] = p.P50
		rep.Metrics[metric+".p95"] = p.P95
		rep.Metrics[metric+".p99"] = p.P99
	}
	for _, name := range []string{"route_misses", "ws.reconnects", "http.network_error", "errors"} {
		rep.Metrics[name] = float64(rep.Counts[name])
	}

	if invariantErr != nil {
		rep.Problems = append(rep.Problems, "the invariant check failed: "+invariantErr.Error())
	} else if invariants != nil {
		for _, v := range invariants.Violations {
			rep.Problems = append(rep.Problems, fmt.Sprintf("invariant %s violated: %s %v", v.Invariant, v.Detail,
				v.IDs))
		}
	}
	metrics := make([]string, 0, len(s.Expect))
	for metric := range s.Expect {
		metrics = append(metrics, metric)
	}
	sort.Strings(metrics)
	for _, metric := range metrics {
		bound := s.Expect[metric]
		check := Check{Metric: metric, Min: bound.Min, Max: bound.Max, Met: true}
		if value, ok := rep.Metrics[metric]; ok {
			check.Value = &value
			check.Met = (bound.Min == nil || value >= *bound.Min) && (bound.Max == nil || value <= *bound.Max)
		} else {
			check.Met = false
		}
		if !check.Met {
			rep.Problems = append(rep.Problems, "expectation not met: "+check.String())
		}
		rep.Checks = append(rep.Checks, check)
	}
	return rep
}

func (c Check) String() string {
	var parts []string
	if c.Min != nil {
		parts = append(parts, fmt.Sprintf("≥ %g", *c.Min))
	}
	if c.Max != nil {
		parts = append(parts, fmt.Sprintf("≤ %g", *c.Max))
	}
	value := "missing"
	if c.Value != nil {
		value = fmt.Sprintf("%.4g", *c.Value)
	}
	return fmt.Sprintf("%s %s: %s", c.Metric, strings.Join(parts, " and "), value)
}

// OK is true when the run met every expectation and broke no invariant.
func (rep *Report) OK() bool {
	return len(rep.Problems) == 0
}

// Save writes the report as JSON.
func (rep *Report) Save(path string) error {
	data, err := json.MarshalIndent(rep, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0o644)
}

// Summary writes the text summary.
func (rep *Report) Summary(w io.Writer) {
	m := rep.Metrics
	fmt.Fprintf(w, "Scenario %s, seed %d, %s\n", rep.Scenario, rep.Seed,
		rep.EndedAt.Sub(rep.StartedAt).Round(time.Second))
	fmt.Fprintf(w, "Rides: %.0f booked, %.0f assigned, %.0f completed; %.0f cancelled searching, %.0f cancelled "+
		"assigned, %.0f no-shows, %.0f not found, %.0f by the system, %.0f unfinished\n", m["rides.booked"],
		m["rides.assigned"], m["rides.completed"], m["rides.rider_cancelled_searching"],
		m["rides.rider_cancelled_assigned"], m["rides.no_show"], m["rides.driver_not_found"],
		m["rides.cancelled_by_system"], m["rides.unfinished"])
	fmt.Fprintf(w, "Rates: match %.1f%%, completion %.1f%%, cancellation %.1f%%, not found %.1f%%; %.2f offers a ride\n",
		100*m["rates.match"], 100*m["rates.completion"], 100*m["rates.cancel"], 100*m["rates.not_found"],
		m["offers_per_ride"])
	for _, metric := range []string{WaitToAssign, WaitToPickup, PickupDistance, PositionLatency} {
		if p, ok := rep.Distributions[metric]; ok {
			fmt.Fprintf(w, "%-20s p50 %8.1f  p95 %8.1f  p99 %8.1f  max %8.1f  (n=%d)\n", metric, p.P50, p.P95, p.P99,
				p.Max, p.Count)
		}
	}
	fmt.Fprintf(w, "Wire: %.0f WebSocket reconnects, %.0f network errors, %d retries, %.0f route misses\n",
		m["ws.reconnects"], m["http.network_error"], rep.Counts["retries"], m["route_misses"])
	if rep.Invariants != nil {
		fmt.Fprintf(w, "Invariants: %d checked, %d violations\n", len(rep.Invariants.Checks),
			len(rep.Invariants.Violations))
	}
	for _, c := range rep.Checks {
		mark := "ok  "
		if !c.Met {
			mark = "FAIL"
		}
		fmt.Fprintf(w, "  %s %s\n", mark, c)
	}
	for _, p := range rep.Problems {
		fmt.Fprintf(w, "PROBLEM: %s\n", p)
	}
}
