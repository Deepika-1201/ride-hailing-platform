package main

import (
	"fmt"
	"strings"
	"testing"
)

func traceEvent(eventID, kind, aggregateID string, version int64) string {
	return fmt.Sprintf("{\"event_id\":%q,\"aggregate_type\":%q,\"aggregate_id\":%q,\"aggregate_version\":%d}\n",
		eventID, kind, aggregateID, version)
}

func TestOutboxTraceAllowsInterleavedAggregatesAndLateDuplicates(t *testing.T) {
	first := traceEvent("first", "ride", "shared-id", 0)
	second := traceEvent("second", "ride", "shared-id", 1)
	otherType := traceEvent("payment", "payment", "shared-id", 20)
	otherRide := traceEvent("other", "ride", "other-id", 8)
	equalVersion := traceEvent("another", "ride", "shared-id", 1)
	expected := first + second + otherType + otherRide + equalVersion
	received := otherType + first + otherRide + second + first + equalVersion

	report, err := verifyOutboxTrace(strings.NewReader(expected), strings.NewReader(received))

	if err != nil || !report.ok() || report.Expected != 5 || report.Unique != 5 || report.Received != 6 || report.Duplicates != 1 {
		t.Fatalf("report %+v, error %v", report, err)
	}
}

func TestOutboxTraceDetectsLossUnexpectedEventsConflictsAndReordering(t *testing.T) {
	first := traceEvent("first", "ride", "ride-1", 0)
	second := traceEvent("second", "ride", "ride-1", 1)
	cases := []struct {
		name       string
		received   string
		missing    int
		unexpected int
		conflicts  int
		order      int
	}{
		{name: "loss", received: first, missing: 1},
		{name: "unexpected", received: first + second + traceEvent("unknown", "ride", "ride-1", 2), unexpected: 1},
		{name: "wrong version", received: first + traceEvent("second", "ride", "ride-1", 2), missing: 1, conflicts: 1},
		{name: "wrong aggregate", received: first + traceEvent("second", "ride", "ride-2", 1), missing: 1, conflicts: 1},
		{name: "conflicting redelivery", received: first + second + traceEvent("first", "payment", "ride-1", 0), conflicts: 1},
		{name: "reordered", received: second + first, order: 1},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			report, err := verifyOutboxTrace(strings.NewReader(first+second), strings.NewReader(testCase.received))
			if err != nil || report.ok() || report.Missing != testCase.missing || report.Unexpected != testCase.unexpected ||
				report.Conflicts != testCase.conflicts || report.OrderViolations != testCase.order {
				t.Fatalf("report %+v, error %v", report, err)
			}
		})
	}
}

func TestOutboxTraceRejectsInvalidLedgersAndMalformedInput(t *testing.T) {
	valid := traceEvent("event", "ride", "ride-1", 0)
	for _, expected := range []string{"", "not json", "{}", valid + valid, traceEvent("event", "ride", "ride-1", -1)} {
		if _, err := verifyOutboxTrace(strings.NewReader(expected), strings.NewReader(valid)); err == nil {
			t.Errorf("accepted invalid ledger %q", expected)
		}
	}
	for _, received := range []string{"{", "null", `{"event_id":"event","aggregate_type":"ride","aggregate_id":"ride-1"}`} {
		if _, err := verifyOutboxTrace(strings.NewReader(valid), strings.NewReader(received)); err == nil {
			t.Errorf("accepted invalid received trace %q", received)
		}
	}
}

func TestOutboxTraceReportsAnEmptyDeliveryAndBoundsExamples(t *testing.T) {
	var expected strings.Builder
	for version := range 30 {
		expected.WriteString(traceEvent(fmt.Sprintf("event-%02d", version), "ride", "ride-1", int64(version)))
	}

	report, err := verifyOutboxTrace(strings.NewReader(expected.String()), strings.NewReader(""))

	if err != nil || report.ok() || report.Missing != 30 || len(report.Violations) != 20 {
		t.Fatalf("report %+v, error %v", report, err)
	}
}

func TestOutboxTraceCommandHelpAndRequiredArguments(t *testing.T) {
	if err := runOutboxVerify([]string{"-h"}); err != nil {
		t.Fatalf("help failed: %v", err)
	}
	if err := runOutboxVerify(nil); err == nil {
		t.Fatal("trace paths are required")
	}
}
