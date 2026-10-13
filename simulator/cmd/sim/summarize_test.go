package main

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/report"
)

func TestSummarizeReportsIncompleteCapturesWithoutStartingAWorkload(t *testing.T) {
	directory := t.TempDir()
	secret := "private-token-do-not-publish"
	if err := os.WriteFile(filepath.Join(directory, "tokens.json"), []byte(secret), 0o600); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	code := run(context.Background(), []string{"summarize", "-capture", directory}, &stdout, &stderr)
	var summary report.CaptureSummary
	if err := json.Unmarshal(stdout.Bytes(), &summary); err != nil {
		t.Fatal(err)
	}
	if code != exitFailed || summary.Status != "CHECKS_FAILED" || len(summary.Issues) == 0 ||
		stderr.Len() != 0 || strings.Contains(stdout.String(), secret) {
		t.Fatalf("code %d, summary %+v, stderr %s", code, summary, stderr.String())
	}
}

func TestSummarizeWritesOnlyANewFileAndPreservesExistingEvidence(t *testing.T) {
	directory := t.TempDir()
	output := filepath.Join(directory, "summary.json")
	var stdout, stderr bytes.Buffer
	args := []string{"summarize", "-capture", directory, "-out", output}
	if code := run(context.Background(), args, &stdout, &stderr); code != exitFailed || stdout.Len() != 0 {
		t.Fatalf("first write: code %d, stdout %s, stderr %s", code, stdout.String(), stderr.String())
	}
	before, err := os.ReadFile(output)
	if err != nil {
		t.Fatal(err)
	}
	if code := run(context.Background(), args, &stdout, &stderr); code != exitCantRun {
		t.Fatalf("overwrite returned %d", code)
	}
	after, err := os.ReadFile(output)
	if err != nil || !bytes.Equal(before, after) {
		t.Fatal("existing output changed")
	}
}

func TestSummarizeRequiresAnExplicitCaptureAndRejectsUnknownArguments(t *testing.T) {
	for _, args := range [][]string{{"summarize"}, {"summarize", "-unknown"}, {"summarize", "-capture", t.TempDir(), "extra"}} {
		var stdout, stderr bytes.Buffer
		if code := run(context.Background(), args, &stdout, &stderr); code != exitCantRun || stdout.Len() != 0 || stderr.Len() == 0 {
			t.Fatalf("%v: code %d, stdout %s, stderr %s", args, code, stdout.String(), stderr.String())
		}
	}
}
