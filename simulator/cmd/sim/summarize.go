package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/report"
)

func summarize(args []string, stdout, stderr io.Writer) int {
	flags := flag.NewFlagSet("summarize", flag.ContinueOnError)
	flags.SetOutput(stderr)
	directory := flags.String("capture", "", "existing collector output directory (required); does not run load")
	output := flags.String("out", "", "new JSON summary file; defaults to stdout and never overwrites a file")
	if err := flags.Parse(args); err != nil {
		return exitCantRun
	}
	if *directory == "" || flags.NArg() != 0 {
		fmt.Fprintln(stderr, "sim summarize: -capture is required; no positional arguments are accepted")
		return exitCantRun
	}
	summary := report.SummariseCapture(*directory)
	data, err := json.MarshalIndent(summary, "", "  ")
	if err != nil {
		fmt.Fprintln(stderr, "sim summarize: cannot encode the summary")
		return exitCantRun
	}
	data = append(data, '\n')
	if *output == "" {
		_, err = stdout.Write(data)
	} else {
		var file *os.File
		file, err = os.OpenFile(*output, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if err == nil {
			_, err = file.Write(data)
			if closeErr := file.Close(); err == nil {
				err = closeErr
			}
		}
	}
	if err != nil {
		fmt.Fprintln(stderr, "sim summarize: cannot write the summary; the output must be new and writable")
		return exitCantRun
	}
	if summary.Status != "CHECKS_PASSED" {
		return exitFailed
	}
	return exitOK
}
