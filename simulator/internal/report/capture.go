package report

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/scenario"
)

type CaptureSummary struct {
	Status              string                 `json:"status"`
	Scenario            string                 `json:"scenario,omitempty"`
	Seed                uint64                 `json:"seed"`
	ScenarioSHA256      string                 `json:"scenario_sha256,omitempty"`
	ReportSHA256        string                 `json:"report_sha256,omitempty"`
	ConfiguredDrivers   int                    `json:"configured_drivers,omitempty"`
	ConfiguredRiders    int                    `json:"configured_riders,omitempty"`
	RunSeconds          float64                `json:"run_seconds,omitempty"`
	Metrics             map[string]float64     `json:"observed_metrics"`
	Distributions       map[string]Percentiles `json:"observed_distributions"`
	Checks              []Check                `json:"recomputed_checks"`
	InvariantChecks     int                    `json:"invariant_checks"`
	InvariantViolations int                    `json:"invariant_violations"`
	Samples             CaptureSamples         `json:"samples"`
	Issues              []string               `json:"issues"`
	Limitations         []string               `json:"limitations"`
}

type CaptureSamples struct {
	Count           int        `json:"count"`
	IntervalSeconds int        `json:"interval_seconds"`
	FirstAt         *time.Time `json:"first_at,omitempty"`
	LastAt          *time.Time `json:"last_at,omitempty"`
	MaximumGap      float64    `json:"maximum_gap_seconds"`
}

func SummariseCapture(directory string) CaptureSummary {
	summary := CaptureSummary{Status: "CHECKS_FAILED", Metrics: map[string]float64{}, Distributions: map[string]Percentiles{},
		Checks: []Check{}, Issues: []string{}, Limitations: []string{
			"Capture checks are not capacity certification or independent verification of the deployed binary.",
			"Configured population is not observed concurrent population; inspect the resource and Prometheus data separately.",
			"Position latency measures driver send to rider WebSocket receipt, not browser rendering.",
			"Metric samples are checked for file integrity and timing, not Prometheus content semantics.",
		}}
	issue := func(message string) { summary.Issues = append(summary.Issues, message) }
	scenarioBytes, err := captureFile(directory, "scenario.yaml", 1<<20)
	var configured *scenario.Scenario
	if err != nil {
		issue("Scenario file is missing, unreadable, non-regular or too large.")
	} else {
		summary.ScenarioSHA256 = digest(scenarioBytes)
		configured, err = scenario.Parse(scenarioBytes, filepath.Join(directory, "scenario.yaml"))
		if err != nil {
			issue("Captured scenario is invalid.")
		} else {
			summary.Scenario, summary.Seed = configured.Name, configured.Seed
			summary.ConfiguredDrivers, summary.ConfiguredRiders = configured.Drivers.Count, configured.Riders.Count
		}
	}
	var recorded Report
	reportBytes, err := captureFile(directory, "report.json", 16<<20)
	if err != nil || json.Unmarshal(reportBytes, &recorded) != nil {
		issue("Simulator report is missing, unreadable, invalid or too large.")
	} else {
		summary.ReportSHA256 = digest(reportBytes)
		if recorded.StartedAt.IsZero() || !recorded.EndedAt.After(recorded.StartedAt) {
			issue("Simulator report has an invalid run window.")
		} else {
			summary.RunSeconds = recorded.EndedAt.Sub(recorded.StartedAt).Seconds()
			if configured != nil && recorded.EndedAt.Sub(recorded.StartedAt) < configured.Duration {
				issue("Run ended before the configured demand window completed.")
			}
		}
		if configured != nil && (recorded.Scenario != configured.Name || recorded.Seed != configured.Seed) {
			issue("Simulator report and captured scenario identities differ.")
		}
		if len(recorded.Problems) > 0 {
			issue(fmt.Sprintf("Simulator reported %d problem(s); inspect the private report for details.", len(recorded.Problems)))
		}
		for _, value := range recorded.Metrics {
			if math.IsNaN(value) || math.IsInf(value, 0) || value < 0 {
				issue("A reported metric is nonfinite or negative.")
				break
			}
		}
		for _, name := range []string{"rides.booked", "rides.assigned", "rides.completed", "rides.unfinished",
			"rates.match", "rates.completion", "offers", "offers_per_ride", "errors", "http.network_error", "ws.reconnects", "route_misses"} {
			if value, found := recorded.Metrics[name]; found && !math.IsNaN(value) && !math.IsInf(value, 0) && value >= 0 {
				summary.Metrics[name] = value
			}
		}
		for _, name := range []string{WaitToAssign, WaitToPickup, PickupDistance, PositionLatency} {
			if values, found := recorded.Distributions[name]; found && values.Count > 0 {
				if values.Mean < 0 || values.Mean > values.Max || values.P50 < 0 || values.P50 > values.P90 ||
					values.P90 > values.P95 || values.P95 > values.P99 || values.P99 > values.Max {
					issue("An observed distribution has inconsistent values.")
				} else {
					summary.Distributions[name] = values
				}
			}
		}
		if configured != nil {
			names := make([]string, 0, len(configured.Expect))
			for name := range configured.Expect {
				names = append(names, name)
			}
			sort.Strings(names)
			if len(names) == 0 {
				issue("Captured scenario has no acceptance checks.")
			}
			for _, name := range names {
				bound := configured.Expect[name]
				check := Check{Metric: name, Min: bound.Min, Max: bound.Max}
				validBound := bound.Min != nil || bound.Max != nil
				if bound.Min != nil && (math.IsNaN(*bound.Min) || math.IsInf(*bound.Min, 0)) {
					check.Min, validBound = nil, false
				}
				if bound.Max != nil && (math.IsNaN(*bound.Max) || math.IsInf(*bound.Max, 0)) {
					check.Max, validBound = nil, false
				}
				if bound.Min != nil && bound.Max != nil && *bound.Min > *bound.Max {
					validBound = false
				}
				if value, found := recorded.Metrics[name]; found {
					check.Value = &value
					check.Met = validBound && value >= 0 && (bound.Min == nil || value >= *bound.Min) && (bound.Max == nil || value <= *bound.Max)
					for _, distribution := range []string{WaitToAssign, WaitToPickup, PickupDistance, PositionLatency} {
						percentiles, present := summary.Distributions[distribution]
						for suffix, expected := range map[string]float64{".p50": percentiles.P50, ".p95": percentiles.P95, ".p99": percentiles.P99} {
							if name == distribution+suffix && (!present || value != expected) {
								check.Met = false
							}
						}
					}
				}
				if !check.Met {
					issue("A captured scenario acceptance check failed or its metric is missing.")
				}
				summary.Checks = append(summary.Checks, check)
			}
		}
		if recorded.Invariants == nil {
			issue("End-of-run invariant results are missing.")
		} else {
			checked := map[string]bool{}
			for _, name := range recorded.Invariants.Checks {
				checked[name] = true
			}
			summary.InvariantChecks = len(checked)
			summary.InvariantViolations = len(recorded.Invariants.Violations)
			complete := len(checked) == 8 && len(recorded.Invariants.Checks) == 8
			for number := 1; number <= 8; number++ {
				complete = complete && checked[fmt.Sprintf("I%d", number)]
			}
			if !complete || summary.InvariantViolations > 0 {
				issue("Invariant results are incomplete, duplicated or contain violations.")
			}
		}
	}
	exitBytes, err := captureFile(directory, "exit-code.txt", 64)
	if err != nil || strings.TrimSpace(string(exitBytes)) != "0" {
		issue("Collector did not record a successful exit.")
	}
	for _, name := range []string{"metrics-failures.txt", "container-stats-errors.txt", "stop-reason.txt"} {
		if details, statErr := os.Lstat(filepath.Join(directory, name)); statErr == nil && details.Size() > 0 {
			issue("Collector recorded scrape, resource-collection or interruption errors.")
		}
	}
	for _, name := range []string{"metrics-before.prom", "metrics-after.prom", "container-stats.jsonl"} {
		if details, statErr := os.Lstat(filepath.Join(directory, name)); statErr != nil || !details.Mode().IsRegular() || details.Size() == 0 {
			issue("A required before/after metric or resource capture is missing or empty.")
		}
	}
	summary.checkSamples(directory, recorded.StartedAt, recorded.EndedAt)
	if len(summary.Issues) == 0 {
		summary.Status = "CHECKS_PASSED"
	}
	return summary
}

func (summary *CaptureSummary) checkSamples(directory string, started, ended time.Time) {
	intervalBytes, err := captureFile(directory, "sample-interval-seconds.txt", 32)
	interval, parseErr := strconv.Atoi(strings.TrimSpace(string(intervalBytes)))
	if err != nil || parseErr != nil || interval < 1 || interval > 3600 {
		summary.Issues = append(summary.Issues, "Recorded sample interval is missing or outside 1..3600 seconds.")
		return
	}
	summary.Samples.IntervalSeconds = interval
	maximumGap := time.Duration(2*interval+10) * time.Second
	entries, err := os.ReadDir(filepath.Join(directory, "metrics"))
	if err != nil {
		summary.Issues = append(summary.Issues, "Metric sample directory is unreadable.")
		return
	}
	var previous time.Time
	for _, entry := range entries {
		if !strings.HasSuffix(entry.Name(), ".prom") {
			continue
		}
		stamp, parseErr := time.Parse("20060102T150405Z", strings.TrimSuffix(entry.Name(), ".prom"))
		details, statErr := entry.Info()
		if parseErr != nil || statErr != nil || !details.Mode().IsRegular() || details.Size() == 0 {
			summary.Issues = append(summary.Issues, "A metric sample has an invalid timestamp, file type or size.")
			continue
		}
		if previous.IsZero() {
			summary.Samples.FirstAt = &stamp
		} else {
			gap := stamp.Sub(previous)
			summary.Samples.MaximumGap = math.Max(summary.Samples.MaximumGap, gap.Seconds())
			if gap > maximumGap {
				summary.Issues = append(summary.Issues, "Metric samples have a gap exceeding the recorded interval tolerance.")
			}
		}
		previous = stamp
		summary.Samples.LastAt = &stamp
		summary.Samples.Count++
	}
	if summary.Samples.Count < 2 {
		summary.Issues = append(summary.Issues, "Fewer than two usable metric samples were captured.")
	} else if !started.IsZero() && (summary.Samples.FirstAt.After(ended) || summary.Samples.LastAt.Before(started) ||
		summary.Samples.FirstAt.After(started.Add(maximumGap)) || summary.Samples.LastAt.Before(ended.Add(-maximumGap))) {
		summary.Issues = append(summary.Issues, "Metric samples do not cover the reported run window.")
	}
}

func captureFile(directory, name string, maximum int64) ([]byte, error) {
	path := filepath.Join(directory, name)
	details, err := os.Lstat(path)
	if err != nil || !details.Mode().IsRegular() || details.Size() > maximum {
		return nil, fmt.Errorf("invalid capture file %s", name)
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, maximum+1))
	if err != nil || int64(len(data)) > maximum {
		return nil, fmt.Errorf("unreadable or oversized capture file %s", name)
	}
	return data, nil
}

func digest(data []byte) string {
	hash := sha256.Sum256(data)
	return hex.EncodeToString(hash[:])
}
