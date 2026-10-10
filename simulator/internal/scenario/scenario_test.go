package scenario

import (
	"math"
	"math/rand/v2"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
)

// Two zones about 6 km apart: Koramangala and MG Road.
const (
	koramangala = "87618925cffffff"
	mgRoad      = "8761892e9ffffff"
)

func write(t *testing.T, dir, name, content string) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func scenarioFile(t *testing.T, extra string) string {
	dir := t.TempDir()
	write(t, dir, "weights.csv", "# test\ncell,hour,weight\n"+koramangala+",9,3\n"+mgRoad+",9,1\n"+
		koramangala+",10,1\n"+mgRoad+",10,1\n")
	return write(t, dir, "s.yaml", `name: test
seed: 7
duration: 1h
drivers:
  count: 10
  acceptance: {base: 0.85, per_km_penalty: 0.08}
  response_time: {median: 4s, p95: 11s}
riders:
  patience: {median: 4m, p95: 9m}
demand:
  base_per_hour: 400
  start_hour: 9
  zone_weights: weights.csv
`+extra)
}

func TestAScenarioFileIsReadWithDefaults(t *testing.T) {
	s, err := Load(scenarioFile(t, ""))
	if err != nil {
		t.Fatal(err)
	}
	if s.City != "blr" || s.Drivers.First != 1 || s.Riders.Count != 500 || s.Riders.Rates != 0.8 ||
		s.Drain != 15*time.Minute || s.Demand.MinTripM != 1500 || s.Demand.Categories["MINI"] != 0.4 {
		t.Fatalf("defaults %+v", s)
	}
	if s.Drivers.ResponseTime.Median != 4*time.Second || s.Duration != time.Hour {
		t.Fatalf("durations %+v", s)
	}
}

func TestAMistypedFieldIsAnError(t *testing.T) {
	if _, err := Load(scenarioFile(t, "  multiplyer: 3\n")); err == nil || !strings.Contains(err.Error(), "multiplyer") {
		t.Fatalf("err %v", err)
	}
}

func TestEveryProblemInAScenarioIsReportedAtOnce(t *testing.T) {
	dir := t.TempDir()
	path := write(t, dir, "bad.yaml", `name: bad
duration: 10m
drivers: {count: 2001}
riders: {no_show: 1.5}
demand: {base_per_hour: 0, categories: {BIKE: 1}}
`)
	_, err := Load(path)
	if err == nil {
		t.Fatal("want errors")
	}
	for _, want := range []string{"2,000 seeded drivers", "riders.no_show", "patience", "base_per_hour", "zone_weights",
		"BIKE"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("no %q in %v", want, err)
		}
	}
}

func TestASpreadHasItsMedianAndP95(t *testing.T) {
	r := rand.New(rand.NewPCG(1, 2))
	spread := Spread{Median: 4 * time.Second, P95: 11 * time.Second}
	below, belowP95 := 0, 0
	for range 20_000 {
		d := spread.Sample(r)
		if d < spread.Median {
			below++
		}
		if d < spread.P95 {
			belowP95++
		}
	}
	if math.Abs(float64(below)/20_000-0.5) > 0.02 || math.Abs(float64(belowP95)/20_000-0.95) > 0.01 {
		t.Fatalf("below the median %d, below the p95 %d of 20,000", below, belowP95)
	}
	if (Spread{Median: time.Second}).Sample(r) != time.Second {
		t.Fatal("without a p95 a spread is its median")
	}
}

func TestTheSameSeedDrawsTheSameTrips(t *testing.T) {
	s, _ := Load(scenarioFile(t, ""))
	m, err := NewModel(s)
	if err != nil {
		t.Fatal(err)
	}
	a, b, c := m.Trips(1), m.Trips(1), m.Trips(2)
	if len(a) == 0 || len(a) != len(b) {
		t.Fatalf("%d and %d trips", len(a), len(b))
	}
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("trip %d differs: %+v, %+v", i, a[i], b[i])
		}
	}
	if len(c) == len(a) && c[0] == a[0] {
		t.Fatal("another seed draws other trips")
	}
}

func TestArrivalsFollowTheZonesShareOfTheHoursDemand(t *testing.T) {
	s, _ := Load(scenarioFile(t, ""))
	m, _ := NewModel(s)
	fromKoramangala, total := 0, 0
	for seed := range uint64(50) {
		for _, trip := range m.Trips(seed) {
			if trip.At >= s.Duration || (seed == 0 && trip.Source != koramangala && trip.Source != mgRoad) {
				t.Fatalf("trip %+v", trip)
			}
			total++
			if trip.Source == koramangala {
				fromKoramangala++
			}
		}
	}
	// 400 an hour, 3:1 for Koramangala at 9.
	if math.Abs(float64(total)/50-400) > 12 || math.Abs(float64(fromKoramangala)/float64(total)-0.75) > 0.02 {
		t.Fatalf("%.1f trips a run, %.3f from Koramangala", float64(total)/50, float64(fromKoramangala)/float64(total))
	}
}

func TestTripsStartInTheirZoneAndRunAtLeastTheMinimumLength(t *testing.T) {
	s, _ := Load(scenarioFile(t, ""))
	m, _ := NewModel(s)
	for _, trip := range m.Trips(3) {
		if d := geo.DistanceM(trip.Pickup, m.centers[trip.Source]); d > zoneRadiusM+1 {
			t.Fatalf("pickup %.0f m from its zone's centre", d)
		}
		if geo.DistanceM(trip.Pickup, trip.Dropoff) < s.Demand.MinTripM {
			t.Fatalf("trip %+v is too short", trip)
		}
	}
}

func TestAnEventMultipliesItsAreasDemandForItsWindow(t *testing.T) {
	s, err := Load(scenarioFile(t, `  areas:
    STADIUM: {lat: 12.9788, lon: 77.5996, radius_m: 300}
  events:
    - {zone: area:STADIUM, start: 20m, duration: 15m, multiplier: 10}
`))
	if err != nil {
		t.Fatal(err)
	}
	m, err := NewModel(s)
	if err != nil {
		t.Fatal(err)
	}
	stadium := geo.Point{Lat: 12.9788, Lon: 77.5996}
	count := 0
	for seed := range uint64(40) {
		for _, trip := range m.Trips(seed) {
			if trip.Source != "area:STADIUM" {
				continue
			}
			count++
			if trip.At < 20*time.Minute || trip.At >= 35*time.Minute || geo.DistanceM(trip.Pickup, stadium) > 301 {
				t.Fatalf("event trip %+v", trip)
			}
		}
	}
	// The average zone has 200 an hour; the event adds 9 × 200 an hour for a quarter of an hour.
	if math.Abs(float64(count)/40-450) > 25 {
		t.Fatalf("%.1f event trips a run", float64(count)/40)
	}
}

func TestDestinationsFollowTheOriginsWeights(t *testing.T) {
	path := scenarioFile(t, "  destinations: od.csv\n")
	write(t, filepath.Dir(path), "od.csv", "origin,destination,weight\n"+koramangala+","+mgRoad+",1\n")
	s, _ := Load(path)
	m, err := NewModel(s)
	if err != nil {
		t.Fatal(err)
	}
	for _, trip := range m.Trips(5) {
		if trip.Source == koramangala && geo.DistanceM(trip.Dropoff, m.centers[mgRoad]) > zoneRadiusM+1 {
			t.Fatalf("a Koramangala trip goes to %v", trip.Dropoff)
		}
	}
}

func TestAnHourWithoutDemandInTheRunIsAnError(t *testing.T) {
	s, _ := Load(scenarioFile(t, ""))
	s.Duration = 3 * time.Hour
	if _, err := NewModel(s); err == nil || !strings.Contains(err.Error(), "hour 11") {
		t.Fatalf("err %v", err)
	}
}

func TestAnEventInAnUnknownAreaIsAnError(t *testing.T) {
	s, _ := Load(scenarioFile(t, "  events:\n    - {zone: area:NOWHERE, start: 0s, duration: 1m, multiplier: 2}\n"))
	if _, err := NewModel(s); err == nil || !strings.Contains(err.Error(), "NOWHERE") {
		t.Fatalf("err %v", err)
	}
}

func TestTheShippedScenariosLoad(t *testing.T) {
	paths, _ := filepath.Glob("../../scenarios/*.yaml")
	if len(paths) == 0 {
		t.Skip("no scenarios yet")
	}
	for _, path := range paths {
		s, err := Load(path)
		if err != nil {
			t.Error(err)
			continue
		}
		if _, err := NewModel(s); err != nil {
			t.Errorf("%s: %v", path, err)
		}
	}
}
