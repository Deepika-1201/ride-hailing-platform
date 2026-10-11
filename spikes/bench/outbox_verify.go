package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"sort"
)

type outboxTraceEvent struct {
	EventID          string `json:"event_id"`
	AggregateType    string `json:"aggregate_type"`
	AggregateID      string `json:"aggregate_id"`
	AggregateVersion *int64 `json:"aggregate_version"`
}

type aggregateIdentity struct {
	kind string
	id   string
}

type eventIdentity struct {
	aggregateIdentity
	version int64
}

func (event outboxTraceEvent) identity() eventIdentity {
	return eventIdentity{aggregateIdentity{event.AggregateType, event.AggregateID}, *event.AggregateVersion}
}

type outboxTraceReport struct {
	Expected        int      `json:"expected"`
	Received        int      `json:"received"`
	Unique          int      `json:"unique"`
	Duplicates      int      `json:"duplicates"`
	Missing         int      `json:"missing"`
	Unexpected      int      `json:"unexpected"`
	Conflicts       int      `json:"conflicts"`
	OrderViolations int      `json:"order_violations"`
	Violations      []string `json:"violations"`
}

func (report outboxTraceReport) ok() bool {
	return report.Missing == 0 && report.Unexpected == 0 && report.Conflicts == 0 && report.OrderViolations == 0
}

func (report *outboxTraceReport) example(detail string) {
	if len(report.Violations) < 20 {
		report.Violations = append(report.Violations, detail)
	}
}

func readOutboxTrace(reader io.Reader, visit func(outboxTraceEvent) error) error {
	decoder := json.NewDecoder(reader)
	for record := 1; ; record++ {
		var event outboxTraceEvent
		if err := decoder.Decode(&event); err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			return fmt.Errorf("record %d: %w", record, err)
		}
		if event.EventID == "" || event.AggregateType == "" || event.AggregateID == "" ||
			event.AggregateVersion == nil || *event.AggregateVersion < 0 {
			return fmt.Errorf("record %d: event and aggregate IDs, aggregate type and a nonnegative version are required", record)
		}
		if err := visit(event); err != nil {
			return fmt.Errorf("record %d: %w", record, err)
		}
	}
}

func verifyOutboxTrace(expected, received io.Reader) (outboxTraceReport, error) {
	report := outboxTraceReport{Violations: []string{}}
	ledger := map[string]eventIdentity{}
	if err := readOutboxTrace(expected, func(event outboxTraceEvent) error {
		if _, exists := ledger[event.EventID]; exists {
			return fmt.Errorf("duplicate ledger event %s", event.EventID)
		}
		ledger[event.EventID] = event.identity()
		return nil
	}); err != nil {
		return report, fmt.Errorf("expected trace: %w", err)
	}
	report.Expected = len(ledger)
	if report.Expected == 0 {
		return report, errors.New("expected trace is empty")
	}
	seen := map[string]bool{}
	lastVersion := map[aggregateIdentity]int64{}
	if err := readOutboxTrace(received, func(event outboxTraceEvent) error {
		report.Received++
		want, exists := ledger[event.EventID]
		if !exists {
			report.Unexpected++
			report.example("unexpected event " + event.EventID)
			return nil
		}
		identity := event.identity()
		if identity != want {
			report.Conflicts++
			report.example("identity/version mismatch for event " + event.EventID)
			return nil
		}
		if seen[event.EventID] {
			report.Duplicates++
			return nil
		}
		seen[event.EventID] = true
		report.Unique++
		previous, exists := lastVersion[identity.aggregateIdentity]
		if exists && identity.version < previous {
			report.OrderViolations++
			report.example(fmt.Sprintf("event %s regressed %s/%s from version %d to %d", event.EventID,
				identity.kind, identity.id, previous, identity.version))
		} else {
			lastVersion[identity.aggregateIdentity] = identity.version
		}
		return nil
	}); err != nil {
		return report, fmt.Errorf("received trace: %w", err)
	}
	missing := make([]string, 0)
	for eventID := range ledger {
		if !seen[eventID] {
			missing = append(missing, eventID)
		}
	}
	sort.Strings(missing)
	report.Missing = len(missing)
	for _, eventID := range missing {
		report.example("missing event " + eventID)
	}
	return report, nil
}

func runOutboxVerify(args []string) error {
	flags := flag.NewFlagSet("outbox-verify", flag.ContinueOnError)
	expectedPath := flags.String("expected", "", "expected event ledger in JSON Lines format")
	receivedPath := flags.String("received", "", "received event trace in JSON Lines format")
	if err := flags.Parse(args); errors.Is(err, flag.ErrHelp) {
		return nil
	} else if err != nil {
		return err
	}
	if *expectedPath == "" || *receivedPath == "" || flags.NArg() != 0 {
		return errors.New("-expected and -received trace paths are required")
	}
	expected, err := os.Open(*expectedPath)
	if err != nil {
		return err
	}
	defer expected.Close()
	received, err := os.Open(*receivedPath)
	if err != nil {
		return err
	}
	defer received.Close()
	report, err := verifyOutboxTrace(expected, received)
	if err != nil {
		return err
	}
	if err := json.NewEncoder(os.Stdout).Encode(report); err != nil {
		return err
	}
	if !report.ok() {
		return errors.New("outbox trace violates delivery invariants")
	}
	return nil
}
