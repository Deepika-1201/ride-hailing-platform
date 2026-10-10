package agent

import (
	"context"
	"fmt"
	"math"
	"math/rand/v2"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/api"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/faults"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/report"
)

// Driver states (LLD §18.3).
const (
	offShift = "off_shift"
	idle     = "idle"
	offered  = "offered"
	toPickup = "to_pickup"
	atPickup = "at_pickup"
	onTrip   = "on_trip"
)

const (
	locationEvery = 4 * time.Second
	stepEvery     = 250 * time.Millisecond
	// arriveWithinM is how close a driver gets before arriving or completing.
	arriveWithinM = 50
	// noShowAfter is the fee rule's waiting time before a no-show (LLD §7.7), and a margin.
	noShowAfter = 5*time.Minute + 2*time.Second
	keptMax     = 1000
	replayBatch = 100
)

// DriverID is seeded driver n's ID (LLD §4.9).
func DriverID(n int) string { return fmt.Sprintf("0199a3f0-0001-7000-8000-%012x", n) }

func vehicleID(n int) string { return fmt.Sprintf("0199a3f0-0003-7000-8000-%012x", n) }

// DriverPhone is seeded driver n's phone number.
func DriverPhone(n int) string { return fmt.Sprintf("+91%d", 7_000_000_000+n) }

type pendingOffer struct {
	id, rideID string
	distanceM  int
	answerAt   time.Time
	answer     bool
	accept     bool
}

type deferred struct {
	rideID, command, key string
	body                 any
}

type scheduledSend struct {
	at     time.Time
	update api.LocationUpdate
	live   bool
}

// Driver is one simulated driver.
type Driver struct {
	w       *World
	n       int
	id      string
	r       *rand.Rand
	s       *api.Session
	rt      *api.Realtime
	plan    *faults.Plan
	pushes  chan api.Message
	resyncs chan struct{}

	motion    *Motion
	accuracy  float64
	seq       int64
	state     string
	offer     *pendingOffer
	ride      *api.Ride
	arrivedAt time.Time
	cancelAt  time.Time
	noShowAt  time.Time
	driftAt   time.Time
	onlineAt  time.Time
	shiftOver bool

	offlineUntil time.Time
	kept         []api.LocationUpdate
	replayAt     time.Time
	scheduled    []scheduledSend
	held         *api.LocationUpdate
	deferred     []deferred
}

// NewDriver prepares seeded driver n; SignIn must succeed before Run.
func NewDriver(w *World, n int) *Driver {
	r := w.Rand(1, n)
	return &Driver{w: w, n: n, id: DriverID(n), r: r, plan: faults.NewPlan(r, w.Scenario.Faults,
		w.Scenario.Duration+w.Scenario.Drain), pushes: make(chan api.Message, 64), resyncs: make(chan struct{}, 1),
		accuracy: 4 + 11*r.Float64(), state: offShift}
}

func (d *Driver) SignIn(ctx context.Context) error {
	s, err := d.w.Client.SignIn(ctx, DriverPhone(d.n), d.w.Code, d.w.Tokens)
	d.s = s
	return err
}

// Run drives a shift: online at the shift's start, offline once the run's demand is over and the driver is free.
func (d *Driver) Run(ctx context.Context, shiftOver <-chan struct{}) {
	start := time.Duration(d.r.Float64() * float64(d.w.Scenario.Drivers.Shift.StartSpread))
	d.motion = NewMotion(d.w.Model.Hotspot(d.r, start))
	if !sleep(ctx, time.Until(d.w.Start.Add(start))) {
		return
	}
	socketCtx, stopSocket := context.WithCancel(ctx)
	defer stopSocket()
	d.rt = d.s.Realtime(d.w.WSURL, func(context.Context) {
		select {
		case d.resyncs <- struct{}{}:
		default:
		}
	}, func(m api.Message) {
		select {
		case d.pushes <- m:
		default:
			d.w.Report.Count("pushes.dropped", 1)
		}
	})
	go d.rt.Run(socketCtx)
	defer func() { d.w.Report.Count("ws.reconnects", d.rt.Reconnects.Load()) }()
	d.goOnline(ctx, time.Now())

	location := time.NewTicker(locationEvery)
	defer location.Stop()
	step := time.NewTicker(stepEvery)
	defer step.Stop()
	last := time.Now()
	for {
		select {
		case <-ctx.Done():
			return
		case <-shiftOver:
			shiftOver = nil
			d.shiftOver = true
		case m := <-d.pushes:
			d.push(ctx, m, time.Now())
		case <-d.resyncs:
			d.resync(ctx, time.Now())
		case now := <-location.C:
			d.sendLocation(now)
		case now := <-step.C:
			d.motion.Advance(now.Sub(last))
			last = now
			d.step(ctx, now)
			if d.shiftOver && d.free() && len(d.deferred) == 0 && len(d.kept) == 0 {
				d.goOffline(ctx)
				return
			}
		}
	}
}

func (d *Driver) free() bool {
	return (d.state == idle || d.state == offShift) && d.offer == nil
}

func (d *Driver) offline(now time.Time) bool {
	return now.Before(d.offlineUntil)
}

func (d *Driver) deviceTime(now time.Time) time.Time {
	return now.Add(d.plan.ClockSkew).UTC()
}

func (d *Driver) failed(what string, err error) {
	code := api.CodeOf(err)
	if code == "" {
		code = "other"
	}
	d.w.Report.Count("errors", 1)
	d.w.Report.Count("errors."+what+"."+code, 1)
}

func (d *Driver) goOnline(ctx context.Context, now time.Time) {
	d.onlineAt = time.Time{}
	if d.offline(now) {
		d.onlineAt = d.offlineUntil
		return
	}
	if _, err := d.s.GoOnline(ctx, api.NewKey(), vehicleID(d.n)); err != nil {
		if ctx.Err() != nil {
			return
		}
		d.failed("go_online", err)
		if api.CodeOf(err) != "DRIVER_NOT_ELIGIBLE" {
			d.onlineAt = now.Add(10 * time.Second)
		}
		return
	}
	d.state = idle
	d.driftAt = now.Add(time.Duration(d.r.Float64() * float64(time.Minute)))
	d.sendLocation(now)
}

func (d *Driver) goOffline(ctx context.Context) {
	offCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Second)
	defer cancel()
	if _, err := d.s.GoOffline(offCtx, api.NewKey()); err != nil {
		d.failed("go_offline", err)
	}
	d.state = offShift
}

// push handles a server message; pushes are best effort, so resync fills any gap.
func (d *Driver) push(ctx context.Context, m api.Message, now time.Time) {
	switch m.Type {
	case "offer":
		if m.Pickup != nil {
			d.consider(ctx, api.Offer{ID: m.OfferID, RideID: m.RideID, Pickup: *m.Pickup,
				PickupDistanceM: m.PickupDistanceM, ExpiresInMs: m.ExpiresInMs}, now)
		}
	case "offer_withdrawn":
		if d.offer != nil && d.offer.id == m.OfferID {
			d.offer = nil
			d.state = idle
		}
	case "ride_status":
		if d.ride == nil || d.ride.ID != m.RideID || m.Version < d.ride.Version {
			return
		}
		d.ride.Version, d.ride.Status = m.Version, m.Status
		switch m.Status {
		case "DRIVER_ASSIGNED", "DRIVER_ARRIVED", "IN_TRIP":
		default:
			// Cancelled by the rider or the system, or no longer this driver's.
			if len(d.deferred) == 0 {
				d.endRide(now)
			}
		}
	case "driver_status":
		if m.Status == "OFFLINE" && d.state != offShift {
			d.w.Report.Count("drivers.taken_offline."+m.Reason, 1)
			d.offer = nil
			if d.ride == nil {
				d.state = idle
			}
			if m.Reason != "SUSPENDED" && !d.shiftOver {
				d.onlineAt = now.Add(5*time.Second + time.Duration(d.r.Float64()*float64(25*time.Second)))
			}
		}
	}
}

// consider decides an offer: acceptance falls with pickup distance; the answer comes after a response time, or
// never if that's longer than the offer lasts.
func (d *Driver) consider(ctx context.Context, o api.Offer, now time.Time) {
	if d.offer != nil && d.offer.id == o.ID {
		return
	}
	d.w.Report.Offered(o.RideID)
	if d.state != idle || d.offline(now) {
		d.w.Report.Count("offers.while_busy", 1)
		return
	}
	if err := d.rt.Send(map[string]string{"type": "offer_seen", "offer_id": o.ID}); err != nil {
		d.w.Report.Count("offers.not_seen", 1)
	}
	a := d.w.Scenario.Drivers.Acceptance
	p := a.Base - a.PerKmPenalty*float64(o.PickupDistanceM)/1000
	if d.shiftOver {
		p = 0
	}
	response := d.w.Scenario.Drivers.ResponseTime.Sample(d.r)
	d.offer = &pendingOffer{id: o.ID, rideID: o.RideID, distanceM: o.PickupDistanceM, answerAt: now.Add(response),
		answer: response < time.Duration(o.ExpiresInMs-300)*time.Millisecond, accept: d.r.Float64() < p}
	d.state = offered
}

func (d *Driver) step(ctx context.Context, now time.Time) {
	for _, o := range d.plan.Due(d.w.Since()) {
		d.w.Report.Count("faults."+o.Kind, 1)
		switch o.Kind {
		case faults.Disconnect:
			d.rt.Disconnect(now.Add(o.Duration))
		case faults.Restart:
			d.rt.Disconnect(now.Add(o.Duration))
			d.forget()
		case faults.Offline:
			d.offlineUntil = now.Add(o.Duration)
			d.rt.Disconnect(d.offlineUntil)
		}
	}
	d.flushScheduled(now)
	if !d.offline(now) {
		if !d.runDeferred(ctx, now) {
			return
		}
		d.replay(ctx, now)
	}
	if !d.onlineAt.IsZero() && !now.Before(d.onlineAt) && d.ride == nil {
		d.goOnline(ctx, now)
	}
	switch d.state {
	case offered:
		if !now.Before(d.offer.answerAt) {
			d.answer(ctx, now)
		}
	case toPickup:
		if !d.cancelAt.IsZero() && !now.Before(d.cancelAt) && !d.offline(now) {
			d.cancel(ctx, now)
		} else if geo.DistanceM(d.motion.At(), d.ride.Pickup) <= arriveWithinM {
			d.arrive(ctx, now)
		} else if !d.motion.Moving() {
			d.driveTo(ctx, d.ride.Pickup, 1)
		}
	case atPickup:
		if pin, ok := d.w.Handover.Take(d.ride.ID); ok {
			d.start(ctx, pin, now)
		} else if !now.Before(d.noShowAt) && !d.offline(now) {
			d.noShow(ctx, now)
		}
	case onTrip:
		if geo.DistanceM(d.motion.At(), d.ride.Dropoff) <= arriveWithinM {
			d.complete(ctx, now)
		} else if !d.motion.Moving() {
			d.driveTo(ctx, d.ride.Dropoff, 1)
		}
	case idle:
		if !d.motion.Moving() && !now.Before(d.driftAt) && !d.shiftOver {
			d.drift(ctx, now)
		}
	}
}

// forget is an app restart: only the sign-in and the stored offline queue survive; resync after the reconnect
// restores the rest.
func (d *Driver) forget() {
	d.offer, d.ride, d.held, d.scheduled = nil, nil, nil, nil
	d.cancelAt = time.Time{}
	if d.state != offShift {
		d.state = idle
	}
	d.motion.Stop()
}

// resync asks the server where things stand after every connect (ADR-006).
func (d *Driver) resync(ctx context.Context, now time.Time) {
	if d.offline(now) || d.state == offShift || len(d.deferred) > 0 {
		return
	}
	status, err := d.s.DriverStatus(ctx)
	if err != nil {
		d.failed("resync", err)
		return
	}
	switch status.Status {
	case "OFFLINE":
		d.offer = nil
		if d.ride != nil {
			d.endRide(now)
		}
		d.state = idle
		if !d.shiftOver && d.onlineAt.IsZero() {
			d.goOnline(ctx, now)
		}
	case "AVAILABLE":
		if d.ride != nil || (d.offer != nil && d.state == offered) {
			d.offer = nil
			d.endRide(now)
		}
	case "OFFERED":
		if d.offer == nil || d.offer.id != status.OfferID {
			if offer, ok, err := d.s.CurrentOffer(ctx); err == nil && ok {
				d.consider(ctx, offer, now)
			}
		}
	case "ASSIGNED", "ON_TRIP":
		if ride, ok, err := d.s.ActiveRide(ctx); err == nil && ok && (d.ride == nil || d.ride.ID != ride.ID) {
			d.adopt(ride, now)
		}
	}
}

// adopt takes over a ride the app had forgotten.
func (d *Driver) adopt(ride api.Ride, now time.Time) {
	d.w.Report.Count("rides.adopted", 1)
	d.ride = &ride
	d.motion.Stop()
	switch ride.Status {
	case "DRIVER_ASSIGNED":
		d.state = toPickup
	case "DRIVER_ARRIVED":
		d.state = atPickup
		d.arrivedAt = now
		if ride.ArrivedAt != nil {
			d.arrivedAt = *ride.ArrivedAt
		}
		d.noShowAt = d.arrivedAt.Add(noShowAfter)
	case "IN_TRIP":
		d.state = onTrip
	}
}

func (d *Driver) answer(ctx context.Context, now time.Time) {
	o := d.offer
	d.offer = nil
	d.state = idle
	if !o.answer || d.offline(now) {
		d.w.Report.Count("offers.unanswered", 1)
		return
	}
	if !o.accept {
		if err := d.s.Decline(ctx, o.id, api.NewKey()); err != nil && api.CodeOf(err) != "OFFER_NO_LONGER_AVAILABLE" {
			d.failed("decline", err)
		}
		d.w.Report.Count("offers.declined", 1)
		return
	}
	ride, err := d.s.Accept(ctx, o.id, api.NewKey())
	if err != nil {
		if api.CodeOf(err) == "OFFER_NO_LONGER_AVAILABLE" {
			d.w.Report.Count("offers.late", 1)
		} else {
			// The answer may have been lost after the server assigned the ride.
			d.failed("accept", err)
			d.resync(ctx, now)
		}
		return
	}
	d.w.Report.Count("offers.accepted", 1)
	d.w.Report.Observe(report.PickupDistance, float64(o.distanceM))
	d.ride = &ride
	d.state = toPickup
	d.driveTo(ctx, ride.Pickup, 1)
	if d.r.Float64() < d.w.Scenario.Drivers.CancelAfterAccept {
		d.cancelAt = now.Add(10*time.Second + time.Duration(d.r.Float64()*float64(50*time.Second)))
	}
}

// command sends a ride command, or keeps it for later while offline (LLD §7.10); false when the ride is no longer
// this driver's.
func (d *Driver) command(ctx context.Context, now time.Time, command string, body map[string]string) bool {
	key := api.NewKey()
	if d.offline(now) {
		if command == "start" || command == "complete" {
			body["device_time"] = d.deviceTime(now).Format(time.RFC3339Nano)
		}
		d.deferred = append(d.deferred, deferred{rideID: d.ride.ID, command: command, key: key, body: body})
		d.w.Report.Count("commands.offline", 1)
		return true
	}
	if _, err := d.s.RideCommand(ctx, d.ride.ID, command, key, body); err != nil {
		d.failed(command, err)
		d.resync(ctx, now)
		return false
	}
	return true
}

func (d *Driver) arrive(ctx context.Context, now time.Time) {
	if !d.command(ctx, now, "arrive", nil) {
		return
	}
	d.motion.Stop()
	d.state = atPickup
	d.arrivedAt = now
	d.noShowAt = now.Add(noShowAfter)
}

func (d *Driver) start(ctx context.Context, pin string, now time.Time) {
	if !d.command(ctx, now, "start", map[string]string{"pin": pin}) {
		return
	}
	d.state = onTrip
	d.driveTo(ctx, d.ride.Dropoff, 1)
}

func (d *Driver) complete(ctx context.Context, now time.Time) {
	if d.command(ctx, now, "complete", map[string]string{}) {
		d.endRide(now)
	}
}

func (d *Driver) cancel(ctx context.Context, now time.Time) {
	d.cancelAt = time.Time{}
	if d.command(ctx, now, "cancel", map[string]string{"reason": "Simulated: driver cancelled"}) {
		d.w.Report.Count("rides.driver_cancelled", 1)
		d.endRide(now)
	}
}

func (d *Driver) noShow(ctx context.Context, now time.Time) {
	_, err := d.s.RideCommand(ctx, d.ride.ID, "no-show", api.NewKey(), nil)
	var p *api.Problem
	switch {
	case err == nil:
		d.endRide(now)
	case api.CodeOf(err) == "NO_SHOW_TOO_EARLY" && asProblem(err, &p) && p.AvailableAt != nil:
		d.noShowAt = p.AvailableAt.Add(time.Second)
	default:
		d.failed("no_show", err)
		d.resync(ctx, now)
	}
}

func (d *Driver) endRide(now time.Time) {
	d.ride = nil
	d.cancelAt = time.Time{}
	if d.state != offShift {
		d.state = idle
	}
	d.driftAt = now.Add(10*time.Second + time.Duration(d.r.Float64()*float64(50*time.Second)))
}

// runDeferred sends the commands made offline, oldest first; false while they still block the state machine.
func (d *Driver) runDeferred(ctx context.Context, now time.Time) bool {
	for len(d.deferred) > 0 {
		c := d.deferred[0]
		_, err := d.s.RideCommand(ctx, c.rideID, c.command, c.key, c.body)
		if err != nil && ctx.Err() != nil {
			return false
		}
		d.deferred = d.deferred[1:]
		if err != nil {
			// The ride moved on while the app was offline, such as being reassigned (OFFLINE_CONFLICT).
			d.w.Report.Count("commands.offline_refused."+api.CodeOf(err), 1)
			rest := d.deferred[:0]
			for _, other := range d.deferred {
				if other.rideID != c.rideID {
					rest = append(rest, other)
				}
			}
			d.deferred = rest
			if d.ride != nil && d.ride.ID == c.rideID {
				d.endRide(now)
			}
			d.resync(ctx, now)
		}
	}
	return true
}

func (d *Driver) drift(ctx context.Context, now time.Time) {
	// Towards the nearest of three likely places for demand at this hour (LLD §18.3).
	best := d.w.Model.Hotspot(d.r, d.w.Since())
	for range 2 {
		if p := d.w.Model.Hotspot(d.r, d.w.Since()); geo.DistanceM(d.motion.At(), p) < geo.DistanceM(d.motion.At(), best) {
			best = p
		}
	}
	d.driftAt = now.Add(time.Minute + time.Duration(d.r.Float64()*float64(2*time.Minute)))
	if geo.DistanceM(d.motion.At(), best) > 300 {
		d.driveTo(ctx, best, 0.7)
	}
}

// driveTo sets off along the router's route, at the scenario's speed factor times factor.
func (d *Driver) driveTo(ctx context.Context, to geo.Point, factor float64) {
	d.motion.Drive(d.w.route(ctx, d.motion.At(), to), factor*d.w.Scenario.Drivers.SpeedFactor)
}

// sendLocation sends the 4-second update over the socket, or keeps it to replay; faults may delay, duplicate or
// reorder it.
func (d *Driver) sendLocation(now time.Time) {
	if d.state == offShift {
		return
	}
	d.seq++
	u := api.LocationUpdate{Seq: d.seq, Lat: d.motion.At().Lat, Lon: d.motion.At().Lon,
		AccuracyM: math.Round(d.accuracy*10) / 10, DeviceTime: d.deviceTime(now)}
	if d.motion.Moving() {
		heading, speed := math.Mod(d.motion.Heading(), 360), d.motion.Speed()
		u.HeadingDeg, u.SpeedMps = &heading, &speed
	}
	if d.offline(now) || !d.rt.Connected() || len(d.kept) > 0 {
		d.keep(u)
		return
	}
	fate := d.plan.Next(d.r)
	if d.held != nil {
		// A reordered update follows the one after it.
		d.scheduled = append(d.scheduled, scheduledSend{now.Add(1200 * time.Millisecond), *d.held, false})
		d.held = nil
		fate.Reorder = false
	}
	switch {
	case fate.Reorder:
		d.w.Report.Count("updates.reordered", 1)
		d.held = &u
	case fate.Delay > 0:
		d.w.Report.Count("updates.delayed", 1)
		d.scheduled = append(d.scheduled, scheduledSend{now.Add(fate.Delay), u, true})
	default:
		d.live(u, now, true)
		if fate.Duplicate {
			d.w.Report.Count("updates.duplicated", 1)
			d.scheduled = append(d.scheduled, scheduledSend{now.Add(1500 * time.Millisecond), u, false})
		}
	}
}

type wsLocation struct {
	Type string `json:"type"`
	api.LocationUpdate
}

// live sends an update over the socket; the first sending of each is timed for the latency report.
func (d *Driver) live(u api.LocationUpdate, now time.Time, first bool) {
	if err := d.rt.Send(wsLocation{"location", u}); err != nil {
		if first {
			d.keep(u)
		}
		return
	}
	if first {
		d.w.Positions.Sent(d.id, u.Seq, now)
	}
}

func (d *Driver) flushScheduled(now time.Time) {
	rest := d.scheduled[:0]
	for _, s := range d.scheduled {
		if now.Before(s.at) {
			rest = append(rest, s)
		} else {
			d.live(s.update, now, s.live)
		}
	}
	d.scheduled = rest
}

func (d *Driver) keep(u api.LocationUpdate) {
	u.Replay = true
	if len(d.kept) == keptMax {
		d.kept = d.kept[1:]
		d.w.Report.Count("updates.lost", 1)
	}
	d.kept = append(d.kept, u)
}

// replay sends kept updates over HTTPS, a batch a second at most (the server's limit).
func (d *Driver) replay(ctx context.Context, now time.Time) {
	if len(d.kept) == 0 || now.Before(d.replayAt) {
		return
	}
	d.replayAt = now.Add(1100 * time.Millisecond)
	batch := d.kept[:min(replayBatch, len(d.kept))]
	result, err := d.s.Replay(ctx, batch)
	if err != nil {
		d.failed("replay", err)
		if api.CodeOf(err) == "" {
			return
		}
	}
	d.w.Report.Count("updates.replayed", int64(len(batch)))
	d.w.Report.Count("updates.replay_applied", int64(result.Applied))
	d.kept = d.kept[len(batch):]
}
