package report

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/api"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/scenario"
)

func ptr(v float64) *float64 { return &v }

func TestPercentilesAreNearestRank(t *testing.T) {
	values := make([]float64, 0, 100)
	for i := 100; i >= 1; i-- {
		values = append(values, float64(i))
	}
	p := Summarise(values)
	if p.Count != 100 || p.P50 != 50 || p.P90 != 90 || p.P95 != 95 || p.P99 != 99 || p.Max != 100 || p.Mean != 50.5 {
		t.Fatalf("%+v", p)
	}
	if (Summarise([]float64{7})).P95 != 7 || (Summarise(nil)).Count != 0 {
		t.Fatal("one value is every percentile; none is an empty summary")
	}
}

func TestAnOutcomeComesFromTheRidesFinalState(t *testing.T) {
	assigned := time.Now()
	cases := map[string]api.Ride{
		Completed:         {Status: "COMPLETED"},
		CancelledSearch:   {Status: "CANCELLED_BY_RIDER"},
		CancelledAssigned: {Status: "CANCELLED_BY_RIDER", AssignedAt: &assigned},
		NoShow:            {Status: "CANCELLED_BY_DRIVER"},
		NotFound:          {Status: "DRIVER_NOT_FOUND"},
		CancelledSystem:   {Status: "CANCELLED_BY_SYSTEM"},
		Unfinished:        {Status: "IN_TRIP"},
	}
	for want, ride := range cases {
		if got := Outcome(ride); got != want {
			t.Errorf("%s: %s, want %s", ride.Status, got, want)
		}
	}
}

func run() *Recorder {
	r := NewRecorder()
	for i, outcome := range []string{Completed, Completed, Completed, NotFound, CancelledSearch} {
		id := string(rune('a' + i))
		r.Booked(id)
		r.Offered(id)
		if outcome != NotFound && outcome != CancelledSearch {
			r.Count("rides.assigned", 1)
			r.Offered(id)
		}
		r.Ended(id, outcome)
		r.Ended(id, CancelledSystem)
	}
	r.Booked("f")
	for _, v := range []float64{10, 20, 30, 40} {
		r.Observe(WaitToAssign, v)
	}
	return r
}

func TestTheReportCountsOutcomesOncePerRideAndDerivesRates(t *testing.T) {
	s := &scenario.Scenario{Name: "t", Seed: 3}
	rep := run().Build(s, time.Unix(0, 0), time.Unix(60, 0), api.NewStats(),
		&api.InvariantReport{Checks: []string{"I1"}}, nil)

	m := rep.Metrics
	if m["rides.booked"] != 6 || m["rides.completed"] != 3 || m["rides.driver_not_found"] != 1 ||
		m["rides.rider_cancelled_searching"] != 1 || m["rides.unfinished"] != 1 || m["rides.cancelled_by_system"] != 0 {
		t.Fatalf("outcomes %v", m)
	}
	if m["rates.match"] != 0.5 || m["rates.completion"] != 0.5 || m["offers_per_ride"] != 8.0/6 ||
		m["rates.not_found"] != 1.0/6 || m["rates.cancel"] != 1.0/6 {
		t.Fatalf("rates %v", m)
	}
	if m["wait_to_assign_s.p50"] != 20 || m["wait_to_assign_s.p95"] != 40 {
		t.Fatalf("waits %v", m)
	}
	if !rep.OK() {
		t.Fatalf("problems %v", rep.Problems)
	}
}

func TestExpectationsAndViolationsMakeTheRunFail(t *testing.T) {
	s := &scenario.Scenario{Name: "t", Expect: map[string]scenario.Bound{
		"rates.match":          {Min: ptr(0.9)},
		"wait_to_assign_s.p95": {Max: ptr(60)},
		"no.such.metric":       {Max: ptr(1)},
	}}
	invariants := &api.InvariantReport{Checks: []string{"I1"},
		Violations: []api.Violation{{Invariant: "I1", Detail: "driver in two rides", IDs: []string{"x"}}}}
	rep := run().Build(s, time.Now(), time.Now(), api.NewStats(), invariants, nil)

	if rep.OK() || len(rep.Checks) != 3 {
		t.Fatalf("checks %+v", rep.Checks)
	}
	met := map[string]bool{}
	for _, c := range rep.Checks {
		met[c.Metric] = c.Met
	}
	if met["rates.match"] || !met["wait_to_assign_s.p95"] || met["no.such.metric"] {
		t.Fatalf("met %v", met)
	}
	text := strings.Join(rep.Problems, "\n")
	for _, want := range []string{"invariant I1 violated", "rates.match ≥ 0.9: 0.5", "no.such.metric ≤ 1: missing"} {
		if !strings.Contains(text, want) {
			t.Errorf("no %q in\n%s", want, text)
		}
	}
}

func TestAnInvariantCheckThatCouldntRunFailsTheRun(t *testing.T) {
	rep := run().Build(&scenario.Scenario{Name: "t"}, time.Now(), time.Now(), api.NewStats(), nil,
		errors.New("503"))
	if rep.OK() {
		t.Fatal("want a problem")
	}
}

func TestTheReportIsSavedAsJSONAndSummarisedAsText(t *testing.T) {
	stats := api.NewStats()
	stats.Latency("POST /v1/quotes", 12*time.Millisecond)
	rep := run().Build(&scenario.Scenario{Name: "t", Expect: map[string]scenario.Bound{"rates.match": {Min: ptr(0.4)}}},
		time.Unix(0, 0), time.Unix(90, 0), stats, &api.InvariantReport{Checks: []string{"I1", "I2"}}, nil)
	path := filepath.Join(t.TempDir(), "report.json")
	if err := rep.Save(path); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	var back Report
	if err := json.Unmarshal(data, &back); err != nil || back.Metrics["rides.booked"] != 6 ||
		back.Endpoints["POST /v1/quotes"].Count != 1 {
		t.Fatalf("%v: %s", err, data)
	}
	var text bytes.Buffer
	rep.Summary(&text)
	for _, want := range []string{"Scenario t, seed 0, 1m30s", "6 booked, 3 assigned, 3 completed", "match 50.0%",
		"wait_to_assign_s", "Invariants: 2 checked, 0 violations", "ok   rates.match ≥ 0.4: 0.5"} {
		if !strings.Contains(text.String(), want) {
			t.Errorf("no %q in\n%s", want, text.String())
		}
	}
}
