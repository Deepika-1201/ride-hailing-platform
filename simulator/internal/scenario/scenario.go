// Package scenario reads scenario files (LLD §18.2) and turns their demand into trips.
package scenario

import (
	"bytes"
	"errors"
	"fmt"
	"math"
	"math/rand/v2"
	"os"
	"path/filepath"
	"time"

	"gopkg.in/yaml.v3"
)

// Scenario is one scenario file. Paths inside it are relative to the file.
type Scenario struct {
	Name     string        `yaml:"name"`
	Seed     uint64        `yaml:"seed"`
	City     string        `yaml:"city"`
	Duration time.Duration `yaml:"duration"`
	// Drain is how long rides still active at the end may take to finish before the run stops waiting.
	Drain   time.Duration    `yaml:"drain"`
	Drivers Drivers          `yaml:"drivers"`
	Riders  Riders           `yaml:"riders"`
	Demand  Demand           `yaml:"demand"`
	Faults  Faults           `yaml:"faults"`
	Expect  map[string]Bound `yaml:"expect"`

	dir string
}

type Drivers struct {
	Count int `yaml:"count"`
	// First is the first seeded driver to use: drivers First to First+Count-1 (LLD §4.9).
	First int `yaml:"first"`
	Shift struct {
		StartSpread time.Duration `yaml:"start_spread"`
	} `yaml:"shift"`
	Acceptance struct {
		Base         float64 `yaml:"base"`
		PerKmPenalty float64 `yaml:"per_km_penalty"`
	} `yaml:"acceptance"`
	ResponseTime      Spread  `yaml:"response_time"`
	CancelAfterAccept float64 `yaml:"cancel_after_accept"`
	// SpeedFactor drives faster than the routes say, so short CI runs finish rides; speeds stay under 120 km/h.
	SpeedFactor float64 `yaml:"speed_factor"`
}

type Riders struct {
	// Count riders take the trips, each one trip at a time; seeded riders 1–500 exist, later ones sign up.
	Count             int     `yaml:"count"`
	First             int     `yaml:"first"`
	Patience          Spread  `yaml:"patience"`
	CancelAfterAssign float64 `yaml:"cancel_after_assign"`
	NoShow            float64 `yaml:"no_show"`
	Rates             float64 `yaml:"rates"`
}

type Demand struct {
	BasePerHour float64 `yaml:"base_per_hour"`
	// StartHour is the local hour the run starts at, choosing the zone weights' hours.
	StartHour    int                `yaml:"start_hour"`
	ZoneWeights  string             `yaml:"zone_weights"`
	Destinations string             `yaml:"destinations"`
	Categories   map[string]float64 `yaml:"categories"`
	MinTripM     float64            `yaml:"min_trip_m"`
	MaxTripM     float64            `yaml:"max_trip_m"`
	Areas        map[string]Area    `yaml:"areas"`
	Events       []Event            `yaml:"events"`
}

// Area is a named place events refer to as area:NAME, such as a stadium.
type Area struct {
	Lat     float64 `yaml:"lat"`
	Lon     float64 `yaml:"lon"`
	RadiusM float64 `yaml:"radius_m"`
}

// Event multiplies a zone's demand for a while (requirements §7's hotspot test).
type Event struct {
	Zone       string        `yaml:"zone"`
	Start      time.Duration `yaml:"start"`
	Duration   time.Duration `yaml:"duration"`
	Multiplier float64       `yaml:"multiplier"`
}

type Faults struct {
	Disconnect Outage `yaml:"disconnect"`
	Restart    struct {
		RatePerHour float64 `yaml:"rate_per_hour"`
	} `yaml:"restart"`
	DelayedUpdates struct {
		Share float64 `yaml:"share"`
		Delay Spread  `yaml:"delay"`
	} `yaml:"delayed_updates"`
	DuplicateUpdates float64 `yaml:"duplicate_updates"`
	ReorderUpdates   float64 `yaml:"reorder_updates"`
	OfflineReplay    Outage  `yaml:"offline_replay"`
	ClockSkew        struct {
		Share float64       `yaml:"share"`
		Max   time.Duration `yaml:"max"`
	} `yaml:"clock_skew"`
}

// Outage is a fault that comes at a rate per agent and lasts a while.
type Outage struct {
	RatePerHour float64 `yaml:"rate_per_hour"`
	Duration    Spread  `yaml:"duration"`
}

// Bound is an expectation on a report metric.
type Bound struct {
	Min *float64 `yaml:"min"`
	Max *float64 `yaml:"max"`
}

// Spread is a log-normal distribution given by its median and 95th percentile; without a p95 it is the median.
type Spread struct {
	Median time.Duration `yaml:"median"`
	P95    time.Duration `yaml:"p95"`
}

// Sample draws a duration.
func (s Spread) Sample(r *rand.Rand) time.Duration {
	if s.P95 <= s.Median || s.Median <= 0 {
		return s.Median
	}
	sigma := math.Log(float64(s.P95)/float64(s.Median)) / 1.6448536269514722
	return time.Duration(float64(s.Median) * math.Exp(sigma*r.NormFloat64()))
}

// Categories the platform sells (LLD §4.9).
var Categories = []string{"AUTO", "MINI", "SEDAN", "XL"}

// Load reads and checks a scenario file, filling in defaults.
func Load(path string) (*Scenario, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	decoder := yaml.NewDecoder(bytes.NewReader(data))
	decoder.KnownFields(true)
	s := &Scenario{dir: filepath.Dir(path)}
	if err := decoder.Decode(s); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	s.defaults()
	if err := s.check(); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	return s, nil
}

// Path resolves a path written in the scenario file.
func (s *Scenario) Path(p string) string {
	if p == "" || filepath.IsAbs(p) {
		return p
	}
	return filepath.Join(s.dir, p)
}

func (s *Scenario) defaults() {
	if s.City == "" {
		s.City = "blr"
	}
	if s.Drain == 0 {
		s.Drain = 15 * time.Minute
	}
	if s.Drivers.First == 0 {
		s.Drivers.First = 1
	}
	if s.Drivers.SpeedFactor == 0 {
		s.Drivers.SpeedFactor = 1
	}
	if s.Riders.First == 0 {
		s.Riders.First = 1
	}
	if s.Riders.Count == 0 {
		s.Riders.Count = 500
	}
	if s.Riders.Rates == 0 {
		s.Riders.Rates = 0.8
	}
	if s.Demand.MinTripM == 0 {
		s.Demand.MinTripM = 1500
	}
	if len(s.Demand.Categories) == 0 {
		// The seeded fleet's mix.
		s.Demand.Categories = map[string]float64{"AUTO": 0.3, "MINI": 0.4, "SEDAN": 0.2, "XL": 0.1}
	}
}

func (s *Scenario) check() error {
	var errs []error
	fail := func(format string, args ...any) { errs = append(errs, fmt.Errorf(format, args...)) }
	share := func(name string, v float64) {
		if v < 0 || v > 1 {
			fail("%s must be between 0 and 1, not %v", name, v)
		}
	}
	if s.Name == "" {
		fail("name is required")
	}
	if s.Duration <= 0 {
		fail("duration must be positive")
	}
	if s.Drivers.Count <= 0 || s.Drivers.First < 1 || s.Drivers.First+s.Drivers.Count-1 > 2000 {
		fail("drivers must be among the 2,000 seeded drivers: first %d, count %d", s.Drivers.First, s.Drivers.Count)
	}
	if s.Riders.Count <= 0 || s.Riders.First < 1 {
		fail("riders.count and riders.first must be positive")
	}
	if s.Drivers.SpeedFactor < 0.1 || s.Drivers.SpeedFactor > 10 {
		fail("drivers.speed_factor must be between 0.1 and 10")
	}
	share("drivers.acceptance.base", s.Drivers.Acceptance.Base)
	share("drivers.cancel_after_accept", s.Drivers.CancelAfterAccept)
	share("riders.cancel_after_assign", s.Riders.CancelAfterAssign)
	share("riders.no_show", s.Riders.NoShow)
	share("riders.rates", s.Riders.Rates)
	share("faults.delayed_updates.share", s.Faults.DelayedUpdates.Share)
	share("faults.duplicate_updates", s.Faults.DuplicateUpdates)
	share("faults.reorder_updates", s.Faults.ReorderUpdates)
	share("faults.clock_skew.share", s.Faults.ClockSkew.Share)
	if s.Riders.Patience.Median <= 0 {
		fail("riders.patience.median is required")
	}
	if s.Demand.BasePerHour <= 0 {
		fail("demand.base_per_hour must be positive")
	}
	if s.Demand.StartHour < 0 || s.Demand.StartHour > 23 {
		fail("demand.start_hour must be 0–23")
	}
	if s.Demand.ZoneWeights == "" {
		fail("demand.zone_weights is required")
	}
	total := 0.0
	for category, weight := range s.Demand.Categories {
		if !known(category) || weight < 0 {
			fail("demand.categories: %s %v", category, weight)
		}
		total += weight
	}
	if total <= 0 {
		fail("demand.categories need a positive weight")
	}
	for name, area := range s.Demand.Areas {
		if area.RadiusM <= 0 {
			fail("demand.areas.%s needs a radius_m", name)
		}
	}
	for i, e := range s.Demand.Events {
		if e.Duration <= 0 || e.Multiplier < 1 {
			fail("demand.events[%d] needs a duration and a multiplier of at least 1", i)
		}
	}
	return errors.Join(errs...)
}

func known(category string) bool {
	for _, c := range Categories {
		if c == category {
			return true
		}
	}
	return false
}
