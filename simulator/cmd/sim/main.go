// Command sim drives the platform with simulated drivers and riders (ADR-021, LLD §18).
//
//	sim run -scenario scenarios/ci.yaml -routes testdata/routes/ci.json
//	sim verify
//
// Exit codes: 0 when the run met its expectations and the invariant check is clean, 1 when it didn't, 2 when it
// couldn't run.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/agent"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/api"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/report"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/router"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/scenario"
)

const (
	exitOK      = 0
	exitFailed  = 1
	exitCantRun = 2
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	os.Exit(run(ctx, os.Args[1:], os.Stdout, os.Stderr))
}

func run(ctx context.Context, args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprintln(stderr, "usage: sim run|verify [flags]; sim run -h for the flags")
		return exitCantRun
	}
	switch args[0] {
	case "run":
		return runScenario(ctx, args[1:], stdout, stderr)
	case "verify":
		return verify(ctx, args[1:], stdout, stderr)
	}
	fmt.Fprintf(stderr, "unknown command %q: use run or verify\n", args[0])
	return exitCantRun
}

type common struct {
	api      *string
	code     *string
	opsPhone *string
	wait     *time.Duration
}

func commonFlags(fs *flag.FlagSet) common {
	return common{
		api:      fs.String("api", "http://localhost:8080", "the platform's base URL"),
		code:     fs.String("code", "123456", "the one-time code the local profile accepts"),
		opsPhone: fs.String("ops-phone", "+919000000001", "the operations user who runs the invariant check"),
		wait:     fs.Duration("wait", 2*time.Minute, "how long to wait for the platform to be ready"),
	}
}

func runScenario(ctx context.Context, args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	fs.SetOutput(stderr)
	c := commonFlags(fs)
	path := fs.String("scenario", "", "the scenario file (required)")
	wsURL := fs.String("ws", "", "the WebSocket URL, instead of the one tickets give")
	osrm := fs.String("osrm", "", "route with this OSRM server")
	routes := fs.String("routes", "", "replay routes recorded in this file; routes it lacks are straight lines")
	record := fs.String("record", "", "write every route the run used to this file")
	reportPath := fs.String("report", "sim-report.json", "write the JSON report here")
	tokens := fs.String("tokens", "", "keep refresh tokens in this file, so reruns sign in without codes")
	parallel := fs.Int("sign-in-parallel", 32, "sign-ins at a time")
	if err := fs.Parse(args); err != nil {
		return exitCantRun
	}
	cantRun := func(err error) int {
		fmt.Fprintln(stderr, "sim:", err)
		return exitCantRun
	}
	if *path == "" {
		return cantRun(errors.New("-scenario is required"))
	}
	if *osrm != "" && *routes != "" {
		return cantRun(errors.New("-osrm and -routes are alternatives"))
	}
	s, err := scenario.Load(*path)
	if err != nil {
		return cantRun(err)
	}
	model, err := scenario.NewModel(s)
	if err != nil {
		return cantRun(err)
	}
	var road router.Router = router.DefaultStraight
	var recorded *router.Recorded
	switch {
	case *osrm != "":
		road = router.NewOSRM(*osrm)
	case *routes != "":
		if recorded, err = router.LoadRecorded(*routes, router.DefaultStraight); err != nil {
			return cantRun(err)
		}
		road = recorded
	default:
		fmt.Fprintln(stderr, "sim: no -osrm or -routes: drivers drive in straight lines")
	}
	var recorder *router.Recorder
	if *record != "" {
		recorder = router.NewRecorder(road)
		road = recorder
	}
	cache, err := api.LoadTokenCache(*tokens)
	if err != nil {
		return cantRun(err)
	}
	client := api.NewClient(*c.api)
	if err := ready(ctx, *c.api, *c.wait); err != nil {
		return cantRun(err)
	}
	ops, err := client.SignIn(ctx, *c.opsPhone, *c.code, cache)
	if err != nil {
		return cantRun(fmt.Errorf("signing in as operations: %w", err))
	}

	w := &agent.World{Scenario: s, Model: model, Client: client, Tokens: cache, Router: road,
		Report: report.NewRecorder(), WSURL: *wsURL, Code: *c.code, Handover: agent.NewHandover(),
		Positions: agent.NewPositions()}
	fmt.Fprintf(stdout, "Scenario %s: %d drivers, %d riders, %s, seed %d\n", s.Name, s.Drivers.Count, s.Riders.Count,
		s.Duration, s.Seed)
	drivers, riders, err := signIn(ctx, w, *parallel)
	_ = cache.Save()
	if err != nil {
		return cantRun(err)
	}

	started := time.Now()
	w.Start = started
	trips := model.Trips(s.Seed)
	fmt.Fprintf(stdout, "Signed in; %d trips over %s\n", len(trips), s.Duration)
	hard, cancel := context.WithDeadline(ctx, started.Add(s.Duration+s.Drain+3*time.Minute))
	defer cancel()

	var driversDone sync.WaitGroup
	shiftOver := make(chan struct{})
	for _, d := range drivers {
		driversDone.Go(func() { d.Run(hard, shiftOver) })
	}
	tripCh := make(chan scenario.Trip)
	wrapUp := make(chan struct{})
	var ridersDone sync.WaitGroup
	for _, rd := range riders {
		ridersDone.Go(func() { rd.Run(hard, tripCh, wrapUp) })
	}
	progress := time.NewTicker(time.Minute)
	defer progress.Stop()
	for _, trip := range trips {
		if !wait(hard, started.Add(trip.At), progress, stdout, w) {
			break
		}
		select {
		case tripCh <- trip:
		default:
			// Every rider is busy: the scenario needs more riders for its demand.
			w.Report.Count("trips.no_free_rider", 1)
		}
	}
	wait(hard, started.Add(s.Duration), progress, stdout, w)
	close(tripCh)
	fmt.Fprintln(stdout, "Demand over; waiting for rides in progress")
	ridersFinished := make(chan struct{})
	go func() { ridersDone.Wait(); close(ridersFinished) }()
	select {
	case <-ridersFinished:
	case <-time.After(time.Until(started.Add(s.Duration + s.Drain))):
		fmt.Fprintln(stdout, "Drain time over; cancelling rides still waiting")
		close(wrapUp)
		<-ridersFinished
	case <-hard.Done():
	}
	close(shiftOver)
	driversDone.Wait()
	ended := time.Now()

	// Let consumers catch up before checking invariants across modules.
	sleepFor(ctx, 5*time.Second)
	invariants, invariantErr := ops.Invariants(ctx)
	var checked *api.InvariantReport
	if invariantErr == nil {
		checked = &invariants
	}
	if recorded != nil {
		w.Report.Count("route_misses", recorded.Misses())
	}
	rep := w.Report.Build(s, started, ended, client.Stats, checked, invariantErr)
	if recorder != nil {
		if err := recorder.Save(*record); err != nil {
			rep.Problems = append(rep.Problems, "recording the routes: "+err.Error())
		}
	}
	_ = cache.Save()
	if err := rep.Save(*reportPath); err != nil {
		rep.Problems = append(rep.Problems, "saving the report: "+err.Error())
	}
	rep.Summary(stdout)
	if !rep.OK() {
		return exitFailed
	}
	return exitOK
}

// signIn signs every agent in, a few at a time; any failure stops the run, since the workload would differ.
func signIn(ctx context.Context, w *agent.World, parallel int) ([]*agent.Driver, []*agent.Rider, error) {
	s := w.Scenario
	drivers := make([]*agent.Driver, s.Drivers.Count)
	riders := make([]*agent.Rider, s.Riders.Count)
	jobs := make(chan func() error)
	errs := make(chan error, s.Drivers.Count+s.Riders.Count)
	var wg sync.WaitGroup
	for range max(1, parallel) {
		wg.Go(func() {
			for job := range jobs {
				if err := job(); err != nil {
					errs <- err
				}
			}
		})
	}
	for i := range drivers {
		drivers[i] = agent.NewDriver(w, s.Drivers.First+i)
		d := drivers[i]
		jobs <- func() error {
			if err := d.SignIn(ctx); err != nil {
				return fmt.Errorf("driver %s: %w", agent.DriverPhone(s.Drivers.First+i), err)
			}
			return nil
		}
	}
	for i := range riders {
		riders[i] = agent.NewRider(w, s.Riders.First+i)
		rd := riders[i]
		jobs <- func() error {
			if err := rd.SignIn(ctx); err != nil {
				return fmt.Errorf("rider %s: %w", agent.RiderPhone(s.Riders.First+i), err)
			}
			return nil
		}
	}
	close(jobs)
	wg.Wait()
	close(errs)
	var all []error
	for err := range errs {
		all = append(all, err)
	}
	if len(all) > 0 {
		return nil, nil, fmt.Errorf("%d sign-ins failed, first: %w", len(all), all[0])
	}
	return drivers, riders, nil
}

// wait sleeps until a time, printing progress every minute; false if the run's context ended.
func wait(ctx context.Context, until time.Time, progress *time.Ticker, out io.Writer, w *agent.World) bool {
	timer := time.NewTimer(time.Until(until))
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return false
		case <-timer.C:
			return true
		case <-progress.C:
			counts, _ := w.Client.Stats.Snapshot()
			fmt.Fprintf(out, "  %s: %d requests answered 2xx, %d retries\n", w.Since().Round(time.Second),
				counts["http.200"]+counts["http.201"]+counts["http.202"]+counts["http.204"], counts["retries"])
		}
	}
}

func sleepFor(ctx context.Context, d time.Duration) {
	select {
	case <-ctx.Done():
	case <-time.After(d):
	}
}

// ready waits for the platform's readiness probe on the API port.
func ready(ctx context.Context, base string, within time.Duration) error {
	deadline := time.Now().Add(within)
	for {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(base, "/")+"/readyz", nil)
		resp, err := http.DefaultClient.Do(req)
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				return nil
			}
		}
		if time.Now().After(deadline) || ctx.Err() != nil {
			return fmt.Errorf("the platform at %s isn't ready: %v", base, err)
		}
		sleepFor(ctx, 2*time.Second)
	}
}

// verify runs the operations invariant check alone.
func verify(ctx context.Context, args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("verify", flag.ContinueOnError)
	fs.SetOutput(stderr)
	c := commonFlags(fs)
	if err := fs.Parse(args); err != nil {
		return exitCantRun
	}
	if err := ready(ctx, *c.api, *c.wait); err != nil {
		fmt.Fprintln(stderr, "sim:", err)
		return exitCantRun
	}
	cache, _ := api.LoadTokenCache("")
	ops, err := api.NewClient(*c.api).SignIn(ctx, *c.opsPhone, *c.code, cache)
	if err != nil {
		fmt.Fprintln(stderr, "sim: signing in as operations:", err)
		return exitCantRun
	}
	result, err := ops.Invariants(ctx)
	if err != nil {
		fmt.Fprintln(stderr, "sim: the invariant check:", err)
		return exitCantRun
	}
	fmt.Fprintf(stdout, "Invariants %s checked at %s: %d violations\n", strings.Join(result.Checks, ", "),
		result.CheckedAt.Format(time.RFC3339), len(result.Violations))
	for _, v := range result.Violations {
		fmt.Fprintf(stdout, "  %s: %s %v\n", v.Invariant, v.Detail, v.IDs)
	}
	if len(result.Violations) > 0 {
		return exitFailed
	}
	return exitOK
}
