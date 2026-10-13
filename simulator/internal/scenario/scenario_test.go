package scenario

import (
	"context"
	"math"
	"math/rand/v2"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
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
	a, b, c := drawTrips(t, m, 1), drawTrips(t, m, 1), drawTrips(t, m, 2)
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
		for _, trip := range drawTrips(t, m, seed) {
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
	for _, trip := range drawTrips(t, m, 3) {
		if d := geo.DistanceM(trip.Pickup, m.centers[trip.Source]); d > zoneRadiusM+1 {
			t.Fatalf("pickup %.0f m from its zone's centre", d)
		}
		if geo.DistanceM(trip.Pickup, trip.Dropoff) < s.Demand.MinTripM {
			t.Fatalf("trip %+v is too short", trip)
		}
	}
}

func drawTrips(t *testing.T, model *Model, seed uint64) []Trip {
	t.Helper()
	trips, err := model.Trips(seed)
	if err != nil {
		t.Fatal(err)
	}
	return trips
}

func TestPortfolioTripsRespectConfiguredDistanceBounds(t *testing.T) {
	configured, err := Load("../../scenarios/portfolio-baseline.yaml")
	if err != nil {
		t.Fatal(err)
	}
	model, err := NewModel(configured)
	if err != nil {
		t.Fatal(err)
	}
	for number, trip := range drawTrips(t, model, configured.Seed) {
		distance := geo.DistanceM(trip.Pickup, trip.Dropoff)
		if distance < configured.Demand.MinTripM || distance > configured.Demand.MaxTripM {
			t.Fatalf("trip %d has %.1fm straight-line distance outside [%.1f, %.1f]", number, distance,
				configured.Demand.MinTripM, configured.Demand.MaxTripM)
		}
	}
}

func TestImpossibleTripBoundsFailInsteadOfReturningAnOutOfRangeWorkload(t *testing.T) {
	configured, err := Load(scenarioFile(t, "  min_trip_m: 100000\n"))
	if err != nil {
		t.Fatal(err)
	}
	model, err := NewModel(configured)
	if err != nil {
		t.Fatal(err)
	}
	trips, err := model.Trips(42)
	if err == nil || !strings.Contains(err.Error(), "no destination within configured trip bounds") || trips != nil {
		t.Fatalf("got partial or out-of-bounds workload: %d trips, %v", len(trips), err)
	}
}

func TestInvalidTripBoundsAreRejectedDuringScenarioLoading(t *testing.T) {
	for _, extra := range []string{"  min_trip_m: -1\n", "  min_trip_m: .nan\n", "  max_trip_m: -1\n",
		"  max_trip_m: .inf\n", "  max_trip_m: 1000\n", "  max_trip_m: 1500\n"} {
		if _, err := Load(scenarioFile(t, extra)); err == nil {
			t.Fatalf("accepted invalid trip bounds %q", extra)
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
		for _, trip := range drawTrips(t, m, seed) {
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
	for _, trip := range drawTrips(t, m, 5) {
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

func TestLaptopBaselineKeepsThirtyMinutesAfterTheDriverRamp(t *testing.T) {
	baseline, err := Load("../../scenarios/laptop-baseline.yaml")
	if err != nil {
		t.Fatal(err)
	}
	if baseline.Drivers.Count != 2000 {
		t.Fatalf("baseline drivers = %d, want 2000", baseline.Drivers.Count)
	}
	if sustained := baseline.Duration - baseline.Drivers.Shift.StartSpread; sustained < 30*time.Minute {
		t.Fatalf("post-ramp demand = %s, want at least 30 minutes", sustained)
	}
	if baseline.Drivers.SpeedFactor != 1 || baseline.Faults != (Faults{}) {
		t.Fatal("the baseline must use normal speeds without injected faults")
	}
	latency, exists := baseline.Expect["position_latency_ms.p95"]
	if !exists || latency.Max == nil || *latency.Max > 1000 {
		t.Fatal("the baseline must enforce position latency p95 at or below one second")
	}
}

func TestPortfolioBaselineIsSeparateBoundedAndUsesNormalDriving(test *testing.T) {
	portfolio, err := Load("../../scenarios/portfolio-baseline.yaml")
	if err != nil {
		test.Fatal(err)
	}
	if portfolio.Name != "portfolio-baseline" || portfolio.City != "blr" || portfolio.Seed != 42 {
		test.Fatalf("unexpected portfolio identity: name=%s city=%s seed=%d", portfolio.Name, portfolio.City, portfolio.Seed)
	}
	if portfolio.Drivers.First != 101 || portfolio.Drivers.Count != 200 || portfolio.Riders.First != 101 || portfolio.Riders.Count != 200 {
		test.Fatalf("unexpected portfolio population: drivers=%+v riders=%+v", portfolio.Drivers, portfolio.Riders)
	}
	if portfolio.Drivers.Shift.StartSpread != time.Minute || portfolio.Duration-portfolio.Drivers.Shift.StartSpread != 10*time.Minute || portfolio.Drain != 10*time.Minute {
		test.Fatalf("unexpected portfolio timing: duration=%s ramp=%s drain=%s", portfolio.Duration, portfolio.Drivers.Shift.StartSpread, portfolio.Drain)
	}
	if portfolio.Drivers.SpeedFactor != 1 || portfolio.Faults != (Faults{}) || portfolio.Drivers.CancelAfterAccept != 0 || portfolio.Riders.CancelAfterAssign != 0 || portfolio.Riders.NoShow != 0 {
		test.Fatal("portfolio baseline must use normal speed without injected faults or cancellations")
	}
	if portfolio.Demand.BasePerHour != 360 || portfolio.Demand.StartHour != 18 || portfolio.Demand.MinTripM != 1200 || portfolio.Demand.MaxTripM != 2500 {
		test.Fatalf("unexpected portfolio demand: %+v", portfolio.Demand)
	}
	for metric, minimum := range map[string]float64{"rides.booked": 40, "rides.completed": 30, "rates.match": 0.85} {
		bound, exists := portfolio.Expect[metric]
		if !exists || bound.Min == nil || *bound.Min != minimum {
			test.Fatalf("portfolio %s must retain minimum %v", metric, minimum)
		}
	}
	for metric, maximum := range map[string]float64{"rides.unfinished": 0, "position_latency_ms.p95": 1000, "errors": 0} {
		bound, exists := portfolio.Expect[metric]
		if !exists || bound.Max == nil || *bound.Max != maximum {
			test.Fatalf("portfolio %s must retain maximum %v", metric, maximum)
		}
	}
	if _, err := NewModel(portfolio); err != nil {
		test.Fatal(err)
	}
	baseline, err := Load("../../scenarios/laptop-baseline.yaml")
	if err != nil {
		test.Fatal(err)
	}
	if baseline.Name == portfolio.Name || baseline.Drivers.Count != 2000 || baseline.Riders.Count != 1200 || baseline.Duration != 35*time.Minute {
		test.Fatal("the portfolio workload must not replace or weaken the original laptop baseline")
	}
}

func TestLaptopCollectorChecksCaptureCompleteness(t *testing.T) {
	script, err := filepath.Abs("../../../scripts/laptop-tier.sh")
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name       string
		gap        time.Duration
		recorded   string
		interval   string
		single     bool
		empty      bool
		badName    bool
		failed     bool
		expectExit int
	}{
		{name: "consecutive", gap: 5 * time.Second, interval: "5"},
		{name: "gap boundary", gap: 20 * time.Second, interval: "5"},
		{name: "gap exceeds boundary", gap: 21 * time.Second, interval: "5", expectExit: 1},
		{name: "recorded interval prevents weaker recheck", gap: 21 * time.Second, recorded: "5", interval: "15", expectExit: 1},
		{name: "recorded interval prevents stricter recheck", gap: 30 * time.Second, recorded: "15", interval: "5"},
		{name: "invalid recorded interval", gap: 5 * time.Second, recorded: "invalid", interval: "5", expectExit: 1},
		{name: "single sample", single: true, interval: "5", expectExit: 1},
		{name: "empty sample", gap: 5 * time.Second, empty: true, interval: "5", expectExit: 1},
		{name: "invalid filename", gap: 5 * time.Second, badName: true, interval: "5", expectExit: 1},
		{name: "failed scrape", gap: 5 * time.Second, failed: true, interval: "5", expectExit: 1},
		{name: "invalid interval argument", gap: 5 * time.Second, interval: "0", expectExit: 2},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			directory := t.TempDir()
			metrics := filepath.Join(directory, "metrics")
			if err := os.Mkdir(metrics, 0o700); err != nil {
				t.Fatal(err)
			}
			start := time.Date(2026, time.October, 11, 8, 0, 0, 0, time.UTC)
			write(t, metrics, start.Format("20060102T150405Z")+".prom", "up 1\n")
			if !testCase.single {
				name := start.Add(testCase.gap).Format("20060102T150405Z") + ".prom"
				if testCase.badName {
					name = "invalid.prom"
				}
				content := "up 1\n"
				if testCase.empty {
					content = ""
				}
				write(t, metrics, name, content)
			}
			if testCase.recorded != "" {
				write(t, directory, "sample-interval-seconds.txt", testCase.recorded+"\n")
			}
			if testCase.failed {
				write(t, directory, "metrics-failures.txt", "20261011T080005Z\n")
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			command := exec.CommandContext(ctx, "bash", script, "--check-samples", directory)
			command.Env = append(os.Environ(), "SAMPLE_INTERVAL="+testCase.interval)
			output, runErr := command.CombinedOutput()
			if command.ProcessState == nil {
				t.Fatalf("starting collector check: %v", runErr)
			}
			if got := command.ProcessState.ExitCode(); got != testCase.expectExit {
				t.Fatalf("exit %d, want %d: %v\n%s", got, testCase.expectExit, runErr, output)
			}
		})
	}
}

func TestLaptopCollectorStopsLoadWhenMetricsStayUnavailable(t *testing.T) {
	for _, mode := range []string{"unavailable", "healthy", "intermittent"} {
		t.Run(mode, func(t *testing.T) {
			script, err := filepath.Abs("../../../scripts/laptop-tier.sh")
			if err != nil {
				t.Fatal(err)
			}
			directory := t.TempDir()
			bin := filepath.Join(directory, "bin")
			if err := os.Mkdir(bin, 0o700); err != nil {
				t.Fatal(err)
			}
			executable := func(name, content string) string {
				t.Helper()
				path := write(t, bin, name, "#!/usr/bin/env bash\nset -eu\n"+content)
				if err := os.Chmod(path, 0o700); err != nil {
					t.Fatal(err)
				}
				return path
			}
			executable("pmset", "printf 'Now drawing from AC Power\\n'\n")
			executable("caffeinate", "exit 0\n")
			executable("docker", "printf '{}\\n'\n")
			executable("curl", `case "${!#}" in
  */actuator/prometheus)
		attempt=0
		if [[ -f "$COLLECTOR_TEST_DIR/scraped" ]]; then read -r attempt < "$COLLECTOR_TEST_DIR/scraped"; fi
		attempt=$((attempt + 1))
		printf '%s\n' "$attempt" > "$COLLECTOR_TEST_DIR/scraped"
		if [[ "$COLLECTOR_TEST_MODE" == unavailable && "$attempt" -gt 1 ]]; then exit 22; fi
		if [[ "$COLLECTOR_TEST_MODE" == intermittent && $((attempt % 3)) -ne 1 ]]; then exit 22; fi
    printf 'up 1\n'
    ;;
  *) printf '{}\n' ;;
esac
`)
			simulator := executable("simulator", `trap 'printf stopped > "$COLLECTOR_TEST_DIR/stopped"; exit 1' TERM
printf 'simulator started\n'
for attempt in {1..60}; do sleep 0.1; done
printf 'simulator ran to completion\n'
`)
			executable("go", `if [[ "$1" == version ]]; then printf 'go version test\n'; exit 0; fi
cp "$COLLECTOR_TEST_SIM" "$3"
`)
			output := filepath.Join(directory, "capture")
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			command := exec.CommandContext(ctx, "bash", script, output)
			command.Env = append(os.Environ(), "PATH="+bin+string(os.PathListSeparator)+os.Getenv("PATH"),
				"SAMPLE_INTERVAL=1", "COLLECTOR_TEST_DIR="+directory, "COLLECTOR_TEST_SIM="+simulator, "COLLECTOR_TEST_MODE="+mode)
			text, runErr := command.CombinedOutput()
			expectedExit := 1
			if mode == "healthy" {
				expectedExit = 0
			}
			if ctx.Err() != nil || command.ProcessState == nil || command.ProcessState.ExitCode() != expectedExit {
				t.Fatalf("collector exit should be %d: %v\n%s", expectedExit, runErr, text)
			}
			if mode == "unavailable" {
				if _, err := os.Stat(filepath.Join(directory, "stopped")); err != nil {
					t.Fatalf("simulator did not receive termination: %v\n%s", err, text)
				}
				reason, err := os.ReadFile(filepath.Join(output, "stop-reason.txt"))
				if err != nil || !strings.Contains(string(reason), "three consecutive") {
					t.Fatalf("missing stop reason: %q, %v", reason, err)
				}
				if strings.Contains(string(text), "ran to completion") {
					t.Fatal("collector waited for the full scenario after metrics failed")
				}
				failures, err := os.ReadFile(filepath.Join(output, "metrics-failures.txt"))
				if err != nil || len(strings.Fields(string(failures))) != 3 {
					t.Fatalf("collector must stop after exactly three failed scrapes: %q, %v", failures, err)
				}
			} else {
				if !strings.Contains(string(text), "ran to completion") {
					t.Fatalf("collector stopped a recoverable run: %s", text)
				}
				if _, err := os.Stat(filepath.Join(output, "stop-reason.txt")); !os.IsNotExist(err) {
					t.Fatalf("collector reported an unexpected automatic stop: %v", err)
				}
				if mode == "intermittent" {
					failures, err := os.ReadFile(filepath.Join(output, "metrics-failures.txt"))
					if err != nil || len(strings.Fields(string(failures))) < 3 {
						t.Fatalf("test did not exercise repeated interrupted failure streaks: %q, %v", failures, err)
					}
				}
			}
			if _, err := os.Stat(filepath.Join(output, ".run-output.pipe")); !os.IsNotExist(err) {
				t.Fatalf("temporary log pipe was not removed: %v", err)
			}
			exit, err := os.ReadFile(filepath.Join(output, "exit-code.txt"))
			if err != nil || strings.TrimSpace(string(exit)) != strconv.Itoa(expectedExit) {
				t.Fatalf("failed run exit code not saved: %q, %v", exit, err)
			}
		})
	}
}
