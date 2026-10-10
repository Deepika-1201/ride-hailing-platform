package agent

import (
	"context"
	"fmt"
	"math/rand/v2"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/api"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/report"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/scenario"
)

// pollEvery is how often a rider reads its ride when pushes are quiet: pushes are best effort.
const pollEvery = 20 * time.Second

// RiderPhone is rider n's phone; riders 1–500 are seeded (LLD §4.9).
func RiderPhone(n int) string { return fmt.Sprintf("+91%d", 8_000_000_000+n) }

// Rider is one simulated rider, taking trips one at a time.
type Rider struct {
	w       *World
	n       int
	r       *rand.Rand
	s       *api.Session
	rt      *api.Realtime
	pushes  chan api.Message
	resyncs chan struct{}

	ride     *api.Ride
	driverID string
	assigned bool
	arrived  bool
	noShow   bool
}

func NewRider(w *World, n int) *Rider {
	return &Rider{w: w, n: n, r: w.Rand(2, n), pushes: make(chan api.Message, 64), resyncs: make(chan struct{}, 1)}
}

// SignIn signs in and reads the profile, which a rider signing up for the first time needs before booking.
func (rd *Rider) SignIn(ctx context.Context) error {
	s, err := rd.w.Client.SignIn(ctx, RiderPhone(rd.n), rd.w.Code, rd.w.Tokens)
	if err != nil {
		return err
	}
	rd.s = s
	_, err = s.Do(ctx, "GET", "/v1/riders/me", "", nil, nil)
	return err
}

// Run takes trips until there are no more, or wrapUp says the run is over.
func (rd *Rider) Run(ctx context.Context, trips <-chan scenario.Trip, wrapUp <-chan struct{}) {
	socketCtx, stopSocket := context.WithCancel(ctx)
	defer stopSocket()
	rd.rt = rd.s.Realtime(rd.w.WSURL, func(context.Context) {
		select {
		case rd.resyncs <- struct{}{}:
		default:
		}
	}, func(m api.Message) {
		select {
		case rd.pushes <- m:
		default:
			rd.w.Report.Count("pushes.dropped", 1)
		}
	})
	go rd.rt.Run(socketCtx)
	defer func() { rd.w.Report.Count("ws.reconnects", rd.rt.Reconnects.Load()) }()
	for {
		select {
		case <-ctx.Done():
			return
		case <-wrapUp:
			return
		case <-rd.pushes:
		case <-rd.resyncs:
		case trip, ok := <-trips:
			if !ok {
				return
			}
			rd.take(ctx, trip, wrapUp)
		}
	}
}

// take is one trip: quote, book, wait, ride, rate (LLD §18.3).
func (rd *Rider) take(ctx context.Context, trip scenario.Trip, wrapUp <-chan struct{}) {
	rd.w.Report.Count("trips.requested", 1)
	quote, err := rd.s.Quote(ctx, trip.Pickup, trip.Dropoff, trip.Category)
	if err != nil {
		rd.failed("quote", err)
		return
	}
	ride, err := rd.s.Book(ctx, api.NewKey(), quote.ID)
	if err != nil {
		rd.failed("book", err)
		return
	}
	rd.w.Report.Booked(ride.ID)
	rd.ride, rd.driverID, rd.assigned, rd.arrived = &ride, "", false, false
	rd.noShow = rd.r.Float64() < rd.w.Scenario.Riders.NoShow
	now := time.Now()
	patienceAt := now.Add(rd.w.Scenario.Riders.Patience.Sample(rd.r))
	var cancelAt time.Time
	if rd.r.Float64() < rd.w.Scenario.Riders.CancelAfterAssign {
		cancelAt = now.Add(15*time.Second + time.Duration(rd.r.Float64()*float64(75*time.Second)))
	}
	pollAt := now.Add(pollEvery)
	step := time.NewTicker(500 * time.Millisecond)
	defer step.Stop()
	for !rd.ride.Ended() {
		select {
		case <-ctx.Done():
			return
		case <-wrapUp:
			// The run is over: leave the system clean for the invariant check and the next run.
			if rd.ride.Status != "IN_TRIP" {
				rd.cancel(ctx, false)
			}
			return
		case m := <-rd.pushes:
			rd.push(ctx, m, time.Now())
		case <-rd.resyncs:
			rd.refresh(ctx)
		case now := <-step.C:
			if !now.Before(pollAt) {
				pollAt = now.Add(pollEvery)
				rd.refresh(ctx)
			}
			switch rd.ride.Status {
			case "SEARCHING":
				if !now.Before(patienceAt) {
					rd.w.Report.Count("riders.out_of_patience", 1)
					rd.cancel(ctx, true)
				}
			case "DRIVER_ASSIGNED":
				if !cancelAt.IsZero() && !now.Before(cancelAt) {
					rd.cancel(ctx, true)
				}
			}
		}
	}
	rd.w.Report.Ended(rd.ride.ID, report.Outcome(*rd.ride))
	if rd.ride.Status == "COMPLETED" && rd.r.Float64() < rd.w.Scenario.Riders.Rates {
		rd.rate(ctx)
	}
}

func (rd *Rider) failed(what string, err error) {
	code := api.CodeOf(err)
	if code == "" {
		code = "other"
	}
	rd.w.Report.Count("errors", 1)
	rd.w.Report.Count("errors."+what+"."+code, 1)
}

func (rd *Rider) push(ctx context.Context, m api.Message, now time.Time) {
	if rd.ride == nil || m.RideID != rd.ride.ID {
		return
	}
	switch m.Type {
	case "ride_status":
		if m.Version > rd.ride.Version {
			rd.refresh(ctx)
		}
	case "driver_position":
		if rd.driverID == "" {
			return
		}
		if latency, ok := rd.w.Positions.Latency(rd.driverID, m.Seq, now); ok {
			rd.w.Report.Observe(report.PositionLatency, float64(latency.Microseconds())/1000)
		}
	}
}

// refresh reads the ride for its details: the driver, the PIN and the times.
func (rd *Rider) refresh(ctx context.Context) {
	if rd.ride == nil {
		return
	}
	ride, err := rd.s.Ride(ctx, rd.ride.ID)
	if err != nil {
		rd.failed("ride", err)
		return
	}
	rd.observe(ride)
}

func (rd *Rider) observe(ride api.Ride) {
	if ride.Version < rd.ride.Version {
		return
	}
	rd.ride = &ride
	if ride.Status == "SEARCHING" {
		// The driver cancelled: the search goes on for another.
		rd.driverID = ""
		return
	}
	if ride.Driver != nil && ride.Driver.ID != rd.driverID && ride.AssignedAt != nil {
		rd.driverID = ride.Driver.ID
		if !rd.assigned {
			rd.assigned = true
			rd.w.Report.Count("rides.assigned", 1)
			rd.w.Report.Observe(report.WaitToAssign, ride.AssignedAt.Sub(ride.RequestedAt).Seconds())
		}
		if !rd.noShow && ride.Pin != "" {
			rd.w.Handover.Give(ride.ID, ride.Pin)
		}
	}
	if ride.ArrivedAt != nil && !rd.arrived {
		rd.arrived = true
		rd.w.Report.Observe(report.WaitToPickup, ride.ArrivedAt.Sub(ride.RequestedAt).Seconds())
	}
}

// cancel cancels the ride; a refusal means its state moved on, which the next read shows.
func (rd *Rider) cancel(ctx context.Context, count bool) {
	ride, err := rd.s.RideCommand(ctx, rd.ride.ID, "cancel", api.NewKey(),
		map[string]string{"reason": "Simulated: rider cancelled"})
	if err != nil {
		if count {
			rd.failed("cancel", err)
		}
		rd.refresh(ctx)
		return
	}
	rd.observe(ride)
}

// rate rates the driver: mostly five stars. The completion may still be being recorded, so a refusal is retried.
func (rd *Rider) rate(ctx context.Context) {
	stars := 5
	switch x := rd.r.Float64(); {
	case x < 0.02:
		stars = 1
	case x < 0.04:
		stars = 2
	case x < 0.10:
		stars = 3
	case x < 0.30:
		stars = 4
	}
	key := api.NewKey()
	for attempt := 0; attempt < 5; attempt++ {
		err := rd.s.Rate(ctx, rd.ride.ID, key, stars)
		if err == nil {
			rd.w.Report.Count("ratings", 1)
			return
		}
		if api.CodeOf(err) != "RATING_NOT_OPEN" || !sleep(ctx, 2*time.Second) {
			rd.failed("rate", err)
			return
		}
	}
}
