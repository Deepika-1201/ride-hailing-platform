package agent

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/api"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/report"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/scenario"
)

func drainRider(t *testing.T, cancellation, refresh http.HandlerFunc) (*Rider, *World) {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/auth/otp", func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusAccepted)
	})
	mux.HandleFunc("POST /v1/auth/token", func(writer http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(writer, `{"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600,"user":{"id":"rider","roles":["RIDER"]}}`)
	})
	mux.HandleFunc("GET /v1/riders/me", func(writer http.ResponseWriter, _ *http.Request) { fmt.Fprint(writer, `{}`) })
	mux.HandleFunc("POST /v1/quotes", func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusCreated)
		fmt.Fprint(writer, `{"id":"quote"}`)
	})
	mux.HandleFunc("POST /v1/rides", func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusCreated)
		fmt.Fprint(writer, `{"id":"ride","status":"SEARCHING","version":1}`)
	})
	mux.HandleFunc("POST /v1/rides/ride/cancel", cancellation)
	mux.HandleFunc("GET /v1/rides/ride", refresh)
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	configured := &scenario.Scenario{Seed: 42}
	configured.Riders.Patience.Median, configured.Riders.Patience.P95 = time.Hour, time.Hour
	cache, err := api.LoadTokenCache("")
	if err != nil {
		t.Fatal(err)
	}
	world := &World{Scenario: configured, Client: api.NewClient(server.URL), Tokens: cache, Code: "123456",
		Report: report.NewRecorder(), Handover: NewHandover(), Positions: NewPositions(), Start: time.Now()}
	rider := NewRider(world, 1)
	if err := rider.SignIn(context.Background()); err != nil {
		t.Fatal(err)
	}
	return rider, world
}

func TestDrainRecordsTheCancellationItSuccessfullyPerformed(t *testing.T) {
	rider, world := drainRider(t, func(writer http.ResponseWriter, _ *http.Request) {
		fmt.Fprint(writer, `{"id":"ride","status":"CANCELLED_BY_RIDER","version":2}`)
	}, func(writer http.ResponseWriter, _ *http.Request) { writer.WriteHeader(http.StatusNotFound) })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	wrapUp := make(chan struct{})
	close(wrapUp)
	rider.take(ctx, scenario.Trip{Category: "MINI"}, wrapUp)
	result := world.Report.Build(world.Scenario, world.Start, time.Now(), world.Client.Stats, &api.InvariantReport{}, nil)
	if result.Metrics["rides.rider_cancelled_searching"] != 1 || result.Metrics["rides.unfinished"] != 0 {
		t.Fatalf("drain cancellation was not recorded: %+v", result.Metrics)
	}
}

func TestDrainKeepsObservingATripThatStartedBeforeCancellation(t *testing.T) {
	refreshed := make(chan struct{})
	var reads atomic.Int32
	rider, world := drainRider(t, func(writer http.ResponseWriter, _ *http.Request) {
		writer.WriteHeader(http.StatusConflict)
		fmt.Fprint(writer, `{"status":409,"code":"INVALID_RIDE_STATE"}`)
	}, func(writer http.ResponseWriter, _ *http.Request) {
		if reads.Add(1) == 1 {
			fmt.Fprint(writer, `{"id":"ride","status":"IN_TRIP","version":2}`)
			close(refreshed)
		} else {
			fmt.Fprint(writer, `{"id":"ride","status":"COMPLETED","version":3}`)
		}
	})
	rider.pushes = make(chan api.Message)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	wrapUp := make(chan struct{})
	close(wrapUp)
	done := make(chan struct{})
	go func() {
		rider.take(ctx, scenario.Trip{Category: "MINI"}, wrapUp)
		close(done)
	}()
	select {
	case <-refreshed:
	case <-ctx.Done():
		t.Fatal("rider did not reconcile the refused cancellation")
	}
	select {
	case rider.pushes <- api.Message{Type: "ride_status", RideID: "ride", Version: 3}:
	case <-done:
		t.Fatal("rider stopped observing an in-progress trip during cleanup")
	case <-ctx.Done():
		t.Fatal("rider did not observe completion")
	}
	select {
	case <-done:
	case <-ctx.Done():
		t.Fatal("rider did not finish after the terminal state")
	}
	result := world.Report.Build(world.Scenario, world.Start, time.Now(), world.Client.Stats, &api.InvariantReport{}, nil)
	if result.Metrics["rides.completed"] != 1 || result.Metrics["rides.unfinished"] != 0 {
		t.Fatalf("completed cleanup trip was not recorded: %+v", result.Metrics)
	}
}
