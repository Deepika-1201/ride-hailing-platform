package report

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/api"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/scenario"
)

const captureScenario = `name: fixture
seed: 42
city: blr
duration: 1m
drivers: {count: 2}
riders: {count: 2, patience: {median: 1m}}
demand: {base_per_hour: 60, zone_weights: unused.csv}
expect: {rides.completed: {min: 1}, errors: {max: 0}}
`

func captureFixture(t *testing.T) string {
	t.Helper()
	directory := t.TempDir()
	if err := os.Mkdir(filepath.Join(directory, "metrics"), 0o700); err != nil {
		t.Fatal(err)
	}
	putCapture(t, directory, "scenario.yaml", captureScenario)
	started := time.Date(2026, 10, 13, 10, 0, 0, 0, time.UTC)
	recorded := Report{Scenario: "fixture", Seed: 42, StartedAt: started, EndedAt: started.Add(time.Minute),
		Metrics:    map[string]float64{"rides.completed": 1, "rides.booked": 1, "errors": 0},
		Invariants: &api.InvariantReport{Checks: []string{"I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8"}}}
	data, err := json.Marshal(recorded)
	if err != nil {
		t.Fatal(err)
	}
	putCapture(t, directory, "report.json", string(data))
	putCapture(t, directory, "exit-code.txt", "0\n")
	putCapture(t, directory, "sample-interval-seconds.txt", "5\n")
	for elapsed := time.Duration(0); elapsed <= time.Minute; elapsed += 5 * time.Second {
		putCapture(t, directory, "metrics/"+started.Add(elapsed).Format("20060102T150405Z")+".prom", "up 1\n")
	}
	for _, name := range []string{"metrics-before.prom", "metrics-after.prom", "container-stats.jsonl"} {
		putCapture(t, directory, name, "fixture\n")
	}
	return directory
}

func putCapture(t *testing.T, directory, name, contents string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(directory, name), []byte(contents), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestCaptureSummaryRechecksEvidenceAndSeparatesConfiguredPopulation(t *testing.T) {
	summary := SummariseCapture(captureFixture(t))
	if summary.Status != "CHECKS_PASSED" || len(summary.Issues) != 0 || summary.Samples.Count != 13 ||
		summary.RunSeconds != 60 || summary.ConfiguredDrivers != 2 || summary.Metrics["rides.completed"] != 1 ||
		summary.InvariantChecks != 8 || len(summary.Checks) != 2 || len(summary.ScenarioSHA256) != 64 || len(summary.ReportSHA256) != 64 {
		t.Fatalf("%+v", summary)
	}
}

func TestCaptureSummaryRejectsMissingFailedAndMismatchedEvidence(t *testing.T) {
	cases := map[string]func(string){
		"no report":                func(directory string) { _ = os.Remove(filepath.Join(directory, "report.json")) },
		"invalid report":           func(directory string) { putCapture(t, directory, "report.json", "{") },
		"failed exit":              func(directory string) { putCapture(t, directory, "exit-code.txt", "1\n") },
		"scrape failure":           func(directory string) { putCapture(t, directory, "metrics-failures.txt", "one failed\n") },
		"resource failure":         func(directory string) { putCapture(t, directory, "container-stats-errors.txt", "unavailable\n") },
		"interrupted":              func(directory string) { putCapture(t, directory, "stop-reason.txt", "stopped\n") },
		"missing interval":         func(directory string) { _ = os.Remove(filepath.Join(directory, "sample-interval-seconds.txt")) },
		"bad interval":             func(directory string) { putCapture(t, directory, "sample-interval-seconds.txt", "0\n") },
		"empty sample":             func(directory string) { putCapture(t, directory, "metrics/20261013T100005Z.prom", "") },
		"bad sample name":          func(directory string) { putCapture(t, directory, "metrics/not-a-time.prom", "up 1\n") },
		"missing resource capture": func(directory string) { _ = os.Remove(filepath.Join(directory, "container-stats.jsonl")) },
		"changed expectations": func(directory string) {
			putCapture(t, directory, "scenario.yaml", strings.Replace(captureScenario, "rides.completed: {min: 1}", "rides.completed: {min: 2}", 1))
		},
		"wrong identity": func(directory string) {
			putCapture(t, directory, "scenario.yaml", strings.Replace(captureScenario, "name: fixture", "name: another", 1))
		},
		"short run": func(directory string) {
			putCapture(t, directory, "scenario.yaml", strings.Replace(captureScenario, "duration: 1m", "duration: 2m", 1))
		},
	}
	for name, damage := range cases {
		t.Run(name, func(t *testing.T) {
			directory := captureFixture(t)
			damage(directory)
			if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" || len(summary.Issues) == 0 {
				t.Fatalf("accepted damaged capture: %+v", summary)
			}
		})
	}
}

func TestCaptureSummaryRequiresSampleCoverageAndAllEightInvariants(t *testing.T) {
	directory := captureFixture(t)
	for _, stamp := range []string{"20261013T100005Z", "20261013T100010Z", "20261013T100015Z", "20261013T100020Z"} {
		_ = os.Remove(filepath.Join(directory, "metrics", stamp+".prom"))
	}
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" || summary.Samples.MaximumGap != 25 {
		t.Fatalf("gap accepted: %+v", summary)
	}
	directory = captureFixture(t)
	data, _ := os.ReadFile(filepath.Join(directory, "report.json"))
	var recorded Report
	_ = json.Unmarshal(data, &recorded)
	recorded.Invariants.Checks = []string{"I1", "I2", "I3", "I4", "I5", "I6", "I7", "I7"}
	data, _ = json.Marshal(recorded)
	putCapture(t, directory, "report.json", string(data))
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" {
		t.Fatalf("incomplete invariant set accepted: %+v", summary)
	}
}

func TestCaptureSummaryRejectsUnbackedPercentilesAndInvalidBounds(t *testing.T) {
	for _, bound := range []string{"{}", "{min: 2, max: 1}", "{max: .nan}"} {
		directory := captureFixture(t)
		putCapture(t, directory, "scenario.yaml", strings.Replace(captureScenario, "errors: {max: 0}", "errors: "+bound, 1))
		summary := SummariseCapture(directory)
		if summary.Status != "CHECKS_FAILED" {
			t.Fatalf("invalid bound %s accepted", bound)
		}
		if _, err := json.Marshal(summary); err != nil {
			t.Fatalf("invalid bound must still yield a serializable failure summary: %v", err)
		}
	}
	directory := captureFixture(t)
	putCapture(t, directory, "scenario.yaml", captureScenario+"\n")
	data, _ := os.ReadFile(filepath.Join(directory, "report.json"))
	var recorded Report
	_ = json.Unmarshal(data, &recorded)
	recorded.Metrics["errors"] = -1
	data, _ = json.Marshal(recorded)
	putCapture(t, directory, "report.json", string(data))
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" {
		t.Fatal("negative errors passed")
	}
	recorded.Metrics["errors"] = 0
	recorded.Metrics["position_latency_ms.p95"] = 12
	putCapture(t, directory, "scenario.yaml", strings.Replace(captureScenario, "errors: {max: 0}", "position_latency_ms.p95: {max: 1000}", 1))
	for _, distributions := range []map[string]Percentiles{nil,
		{PositionLatency: {Count: 1, Mean: 10, P50: 10, P90: 10, P95: 10, P99: 10, Max: 10}}} {
		recorded.Distributions = distributions
		data, _ = json.Marshal(recorded)
		putCapture(t, directory, "report.json", string(data))
		if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" {
			t.Fatal("missing or contradictory percentile distribution passed")
		}
	}
	recorded.Metrics["position_latency_ms.p95"] = 10
	data, _ = json.Marshal(recorded)
	putCapture(t, directory, "report.json", string(data))
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_PASSED" {
		t.Fatalf("consistent percentile rejected: %+v", summary)
	}
}

func TestCaptureSummaryRejectsRatesAndCountsThatContradictEachOther(t *testing.T) {
	cases := map[string]map[string]float64{
		"inflated match rate":      {"rides.booked": 2, "rides.assigned": 1, "rates.match": 1},
		"inflated completion rate": {"rides.booked": 2, "rates.completion": 1},
		"wrong offers per ride":    {"offers": 2, "offers_per_ride": 1},
		"fractional bookings":      {"rides.booked": 1.5},
		"too many completed rides": {"rides.completed": 2},
		"outcomes exceed bookings": {"rides.unfinished": 1},
	}
	for name, changes := range cases {
		t.Run(name, func(t *testing.T) {
			directory := captureFixture(t)
			data, err := os.ReadFile(filepath.Join(directory, "report.json"))
			if err != nil {
				t.Fatal(err)
			}
			var recorded Report
			if err := json.Unmarshal(data, &recorded); err != nil {
				t.Fatal(err)
			}
			for metric, value := range changes {
				recorded.Metrics[metric] = value
			}
			data, err = json.Marshal(recorded)
			if err != nil {
				t.Fatal(err)
			}
			putCapture(t, directory, "report.json", string(data))
			if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" {
				t.Fatalf("accepted inconsistent report: %+v", summary)
			}
		})
	}
}

func TestCaptureSummaryChecksDerivedRatesAgainstRealRecorderOutput(t *testing.T) {
	directory := captureFixture(t)
	configured, err := scenario.Parse([]byte(captureScenario), filepath.Join(directory, "scenario.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	started := time.Date(2026, 10, 13, 10, 0, 0, 0, time.UTC)
	recorder := NewRecorder()
	for _, ride := range []string{"completed", "cancelled", "not-found"} {
		recorder.Booked(ride)
	}
	recorder.Ended("completed", Completed)
	recorder.Ended("cancelled", CancelledSearch)
	recorder.Ended("not-found", NotFound)
	recorder.Count("rides.assigned", 1)
	recorder.Offered("completed")
	recorder.Offered("completed")
	recorded := recorder.Build(configured, started, started.Add(time.Minute), &api.Stats{},
		&api.InvariantReport{Checks: []string{"I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8"}}, nil)
	writeReport := func() {
		t.Helper()
		data, marshalErr := json.Marshal(recorded)
		if marshalErr != nil {
			t.Fatal(marshalErr)
		}
		putCapture(t, directory, "report.json", string(data))
	}
	writeReport()
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_PASSED" || summary.Metrics["rates.match"] != 1.0/3 {
		t.Fatalf("rejected real recorder arithmetic: %+v", summary)
	}
	for _, rate := range []string{"rates.match", "rates.completion", "rates.cancel", "rates.not_found", "offers_per_ride"} {
		original := recorded.Metrics[rate]
		recorded.Metrics[rate] = original + 0.1
		writeReport()
		if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" {
			t.Fatalf("accepted changed %s", rate)
		}
		recorded.Metrics[rate] = original
	}
	recorded.Metrics["rides.booked"] = 4
	recorded.Metrics["rates.match"] = 0.25
	recorded.Metrics["rates.completion"] = 0.25
	recorded.Metrics["rates.cancel"] = 0.25
	recorded.Metrics["rates.not_found"] = 0.25
	recorded.Metrics["offers_per_ride"] = 0.5
	writeReport()
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" {
		t.Fatal("accepted complete outcomes missing one booked ride")
	}
}

func TestCaptureSummaryRejectsRatesWithoutTheirInputsAndAcceptsAbsentRatesForZeroBookings(t *testing.T) {
	for _, missing := range []string{"rides.booked", "rides.assigned"} {
		t.Run(missing, func(t *testing.T) {
			directory := captureFixture(t)
			data, err := os.ReadFile(filepath.Join(directory, "report.json"))
			if err != nil {
				t.Fatal(err)
			}
			var recorded Report
			if err := json.Unmarshal(data, &recorded); err != nil {
				t.Fatal(err)
			}
			recorded.Metrics["rides.assigned"], recorded.Metrics["rates.match"] = 1, 1
			delete(recorded.Metrics, missing)
			data, err = json.Marshal(recorded)
			if err != nil {
				t.Fatal(err)
			}
			putCapture(t, directory, "report.json", string(data))
			if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" {
				t.Fatalf("accepted rate without %s", missing)
			}
		})
	}
	directory := captureFixture(t)
	putCapture(t, directory, "scenario.yaml", strings.Replace(captureScenario, "min: 1", "min: 0", 1))
	configured, err := scenario.Parse([]byte(strings.Replace(captureScenario, "min: 1", "min: 0", 1)), filepath.Join(directory, "scenario.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	started := time.Date(2026, 10, 13, 10, 0, 0, 0, time.UTC)
	recorded := NewRecorder().Build(configured, started, started.Add(time.Minute), &api.Stats{},
		&api.InvariantReport{Checks: []string{"I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8"}}, nil)
	data, err := json.Marshal(recorded)
	if err != nil {
		t.Fatal(err)
	}
	putCapture(t, directory, "report.json", string(data))
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_PASSED" {
		t.Fatalf("zero-booking report with no derived rates rejected: %+v", summary)
	}
	recorded.Metrics["rates.match"] = 0
	data, err = json.Marshal(recorded)
	if err != nil {
		t.Fatal(err)
	}
	putCapture(t, directory, "report.json", string(data))
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" {
		t.Fatal("accepted a rate with zero denominator")
	}
}

func TestCaptureSummaryRejectsSamplesEntirelyOutsideAShortRun(t *testing.T) {
	directory := captureFixture(t)
	putCapture(t, directory, "scenario.yaml", strings.Replace(captureScenario, "duration: 1m", "duration: 5s", 1))
	data, _ := os.ReadFile(filepath.Join(directory, "report.json"))
	var recorded Report
	_ = json.Unmarshal(data, &recorded)
	recorded.EndedAt = recorded.StartedAt.Add(5 * time.Second)
	data, _ = json.Marshal(recorded)
	putCapture(t, directory, "report.json", string(data))
	entries, _ := os.ReadDir(filepath.Join(directory, "metrics"))
	for _, entry := range entries {
		_ = os.Remove(filepath.Join(directory, "metrics", entry.Name()))
	}
	putCapture(t, directory, "metrics/20261013T100006Z.prom", "up 1\n")
	putCapture(t, directory, "metrics/20261013T100007Z.prom", "up 1\n")
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" {
		t.Fatal("samples after the run passed the interval tolerance")
	}
}

func TestCaptureSummaryRejectsOversizedAndLinkedInputFiles(t *testing.T) {
	directory := captureFixture(t)
	if err := os.Truncate(filepath.Join(directory, "report.json"), (16<<20)+1); err != nil {
		t.Fatal(err)
	}
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" || summary.ReportSHA256 != "" {
		t.Fatalf("oversized report accepted: %+v", summary)
	}
	directory = captureFixture(t)
	if err := os.Rename(filepath.Join(directory, "report.json"), filepath.Join(directory, "tokens.json")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("tokens.json", filepath.Join(directory, "report.json")); err != nil {
		t.Fatal(err)
	}
	if summary := SummariseCapture(directory); summary.Status != "CHECKS_FAILED" || summary.ReportSHA256 != "" {
		t.Fatalf("linked report accepted: %+v", summary)
	}
}

func TestCaptureSummaryDoesNotCopyTokensRawProblemsOrUnexpectedMetrics(t *testing.T) {
	directory := captureFixture(t)
	secret := "private-token-must-not-be-published"
	putCapture(t, directory, "tokens.json", secret)
	putCapture(t, directory, "run.log", secret)
	data, _ := os.ReadFile(filepath.Join(directory, "report.json"))
	var recorded Report
	_ = json.Unmarshal(data, &recorded)
	recorded.Problems = []string{secret}
	recorded.Metrics[secret] = 7
	data, _ = json.Marshal(recorded)
	putCapture(t, directory, "report.json", string(data))
	summary := SummariseCapture(directory)
	data, _ = json.Marshal(summary)
	if summary.Status != "CHECKS_FAILED" || strings.Contains(string(data), secret) {
		t.Fatalf("unsafe summary: %s", data)
	}
}
