package faults

import (
	"math"
	"math/rand/v2"
	"testing"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/scenario"
)

func chaos() scenario.Faults {
	var f scenario.Faults
	f.Disconnect = scenario.Outage{RatePerHour: 2, Duration: scenario.Spread{Median: 20 * time.Second}}
	f.Restart.RatePerHour = 1
	f.OfflineReplay = scenario.Outage{RatePerHour: 0.5, Duration: scenario.Spread{Median: 90 * time.Second}}
	f.ClockSkew.Share = 0.1
	f.ClockSkew.Max = 3 * time.Minute
	f.DuplicateUpdates = 0.05
	f.ReorderUpdates = 0.02
	f.DelayedUpdates.Share = 0.03
	f.DelayedUpdates.Delay = scenario.Spread{Median: 2 * time.Second, P95: 10 * time.Second}
	return f
}

func TestOutagesComeAtTheirRatesInTimeOrder(t *testing.T) {
	counts := map[string]int{}
	skewed := 0
	for seed := range uint64(2000) {
		p := NewPlan(rand.New(rand.NewPCG(seed, 1)), chaos(), time.Hour)
		for i, o := range p.Outages {
			counts[o.Kind]++
			if o.At >= time.Hour || (i > 0 && o.At < p.Outages[i-1].At) {
				t.Fatalf("outages out of order or past the run: %+v", p.Outages)
			}
			if o.Kind == Disconnect && o.Duration != 20*time.Second || o.Kind == Restart && o.Duration != restartTakes {
				t.Fatalf("outage %+v", o)
			}
		}
		if p.ClockSkew != 0 {
			skewed++
			if p.ClockSkew.Abs() > 3*time.Minute {
				t.Fatalf("skew %v", p.ClockSkew)
			}
		}
	}
	for kind, want := range map[string]float64{Disconnect: 2, Restart: 1, Offline: 0.5} {
		if got := float64(counts[kind]) / 2000; math.Abs(got-want) > want*0.1 {
			t.Errorf("%s: %.2f an hour, want %.1f", kind, got, want)
		}
	}
	if math.Abs(float64(skewed)/2000-0.1) > 0.02 {
		t.Errorf("%d of 2,000 clocks skewed", skewed)
	}
}

func TestTheSameSeedGivesTheSameFaults(t *testing.T) {
	a := NewPlan(rand.New(rand.NewPCG(5, 1)), chaos(), time.Hour)
	b := NewPlan(rand.New(rand.NewPCG(5, 1)), chaos(), time.Hour)
	if len(a.Outages) != len(b.Outages) || a.ClockSkew != b.ClockSkew {
		t.Fatal("plans differ")
	}
	for i := range a.Outages {
		if a.Outages[i] != b.Outages[i] {
			t.Fatalf("outage %d differs", i)
		}
	}
}

func TestDueOutagesAreTakenOnce(t *testing.T) {
	p := &Plan{Outages: []Outage{{Kind: Restart, At: time.Second}, {Kind: Offline, At: 5 * time.Second}}}
	if due := p.Due(2 * time.Second); len(due) != 1 || due[0].Kind != Restart {
		t.Fatalf("due %v", due)
	}
	if due := p.Due(2 * time.Second); len(due) != 0 {
		t.Fatalf("taken twice: %v", due)
	}
	if due := p.Due(time.Minute); len(due) != 1 || len(p.Outages) != 0 {
		t.Fatalf("due %v, left %v", due, p.Outages)
	}
}

func TestUpdatesAreHeldWithinTheServersRateLimit(t *testing.T) {
	r := rand.New(rand.NewPCG(9, 9))
	p := NewPlan(r, chaos(), time.Hour)
	var reordered, delayed, duplicated int
	for range 100_000 {
		u := p.Next(r)
		if u.Reorder {
			reordered++
			if u.Delay != 0 || u.Duplicate {
				t.Fatalf("a reordered update is only reordered: %+v", u)
			}
		}
		if u.Delay != 0 {
			delayed++
			if u.Delay < 1100*time.Millisecond || u.Delay > MaxDelay || u.Duplicate {
				t.Fatalf("update %+v", u)
			}
		}
		if u.Duplicate {
			duplicated++
		}
	}
	if math.Abs(float64(reordered)/100_000-0.02) > 0.003 || math.Abs(float64(delayed)/100_000-0.03*0.98) > 0.003 ||
		duplicated == 0 {
		t.Fatalf("reordered %d, delayed %d, duplicated %d of 100,000", reordered, delayed, duplicated)
	}
	if (&Plan{}).Next(r) != (Update{}) {
		t.Fatal("no faults, no changes")
	}
}
