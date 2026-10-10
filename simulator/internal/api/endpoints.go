package api

import (
	"context"
	"net/http"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
)

// The fields the simulator reads; the API's JSON is snake_case and clients ignore unknown fields.

type Money struct {
	AmountPaise int64  `json:"amount_paise"`
	Currency    string `json:"currency"`
}

type Quote struct {
	ID         string    `json:"id"`
	DistanceM  int       `json:"distance_m"`
	DurationS  int       `json:"duration_s"`
	PickupEtaS *int      `json:"pickup_eta_s"`
	Surge      float64   `json:"surge_multiplier"`
	ExpiresAt  time.Time `json:"expires_at"`
}

type Person struct {
	ID        string `json:"id"`
	FirstName string `json:"first_name"`
}

type Ride struct {
	ID           string     `json:"id"`
	Status       string     `json:"status"`
	Version      int64      `json:"version"`
	Category     string     `json:"category"`
	Pickup       geo.Point  `json:"pickup"`
	Dropoff      geo.Point  `json:"dropoff"`
	Fare         Money      `json:"fare"`
	Pin          string     `json:"pin"`
	Driver       *Person    `json:"driver"`
	Rider        *Person    `json:"rider"`
	RequestedAt  time.Time  `json:"requested_at"`
	AssignedAt   *time.Time `json:"assigned_at"`
	ArrivedAt    *time.Time `json:"arrived_at"`
	StartedAt    *time.Time `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at"`
	EndedAt      *time.Time `json:"ended_at"`
	Cancellation *struct {
		CancelledBy string `json:"cancelled_by"`
		Reason      string `json:"reason"`
	} `json:"cancellation"`
}

// Ended is true for the five terminal states.
func (r Ride) Ended() bool {
	switch r.Status {
	case "COMPLETED", "CANCELLED_BY_RIDER", "CANCELLED_BY_DRIVER", "CANCELLED_BY_SYSTEM", "DRIVER_NOT_FOUND":
		return true
	}
	return false
}

type Offer struct {
	ID              string    `json:"id"`
	RideID          string    `json:"ride_id"`
	Status          string    `json:"status"`
	Pickup          geo.Point `json:"pickup"`
	Dropoff         geo.Point `json:"dropoff"`
	PickupDistanceM int       `json:"pickup_distance_m"`
	ExpiresInMs     int64     `json:"expires_in_ms"`
}

type DriverStatus struct {
	Status  string `json:"status"`
	Version int64  `json:"version"`
	OfferID string `json:"offer_id"`
	RideID  string `json:"ride_id"`
}

type LocationUpdate struct {
	Seq        int64     `json:"seq"`
	Lat        float64   `json:"lat"`
	Lon        float64   `json:"lon"`
	AccuracyM  float64   `json:"accuracy_m"`
	HeadingDeg *float64  `json:"heading_deg,omitempty"`
	SpeedMps   *float64  `json:"speed_mps,omitempty"`
	DeviceTime time.Time `json:"device_time"`
	Replay     bool      `json:"replay,omitempty"`
}

type LocationResult struct {
	Applied int `json:"applied"`
	Stale   int `json:"stale"`
	Ignored int `json:"ignored"`
}

type Ticket struct {
	Ticket string `json:"ticket"`
	URL    string `json:"url"`
}

type Violation struct {
	Invariant string   `json:"invariant"`
	Detail    string   `json:"detail"`
	IDs       []string `json:"ids,omitempty"`
}

type InvariantReport struct {
	CheckedAt  time.Time   `json:"checked_at"`
	Checks     []string    `json:"checks"`
	Violations []Violation `json:"violations"`
}

func (s *Session) Quote(ctx context.Context, pickup, dropoff geo.Point, category string) (Quote, error) {
	var q Quote
	_, err := s.Do(ctx, http.MethodPost, "/v1/quotes", "",
		map[string]any{"pickup": pickup, "dropoff": dropoff, "category": category}, &q)
	return q, err
}

func (s *Session) Book(ctx context.Context, key, quoteID string) (Ride, error) {
	var r Ride
	_, err := s.Do(ctx, http.MethodPost, "/v1/rides", key, map[string]string{"quote_id": quoteID}, &r)
	return r, err
}

func (s *Session) Ride(ctx context.Context, rideID string) (Ride, error) {
	var r Ride
	_, err := s.Do(ctx, http.MethodGet, "/v1/rides/"+rideID, "", nil, &r)
	return r, err
}

// RideCommand posts cancel, arrive, start, complete or no-show.
func (s *Session) RideCommand(ctx context.Context, rideID, command, key string, body any) (Ride, error) {
	var r Ride
	_, err := s.Do(ctx, http.MethodPost, "/v1/rides/"+rideID+"/"+command, key, body, &r)
	return r, err
}

func (s *Session) Rate(ctx context.Context, rideID, key string, stars int) error {
	_, err := s.Do(ctx, http.MethodPost, "/v1/rides/"+rideID+"/rating", key, map[string]int{"stars": stars}, nil)
	return err
}

func (s *Session) GoOnline(ctx context.Context, key, vehicleID string) (DriverStatus, error) {
	var d DriverStatus
	_, err := s.Do(ctx, http.MethodPost, "/v1/drivers/me/online", key, map[string]string{"vehicle_id": vehicleID}, &d)
	return d, err
}

func (s *Session) GoOffline(ctx context.Context, key string) (DriverStatus, error) {
	var d DriverStatus
	_, err := s.Do(ctx, http.MethodPost, "/v1/drivers/me/offline", key, nil, &d)
	return d, err
}

// DriverStatus reads the driver's availability, from the profile.
func (s *Session) DriverStatus(ctx context.Context) (DriverStatus, error) {
	var me struct {
		Status DriverStatus `json:"status"`
	}
	_, err := s.Do(ctx, http.MethodGet, "/v1/drivers/me", "", nil, &me)
	return me.Status, err
}

// ActiveRide is the driver's active ride; false when there is none.
func (s *Session) ActiveRide(ctx context.Context) (Ride, bool, error) {
	var r Ride
	status, err := s.Do(ctx, http.MethodGet, "/v1/drivers/me/active-ride", "", nil, &r)
	return r, status == http.StatusOK, err
}

// CurrentOffer is the driver's pending offer; false when there is none.
func (s *Session) CurrentOffer(ctx context.Context) (Offer, bool, error) {
	var o Offer
	status, err := s.Do(ctx, http.MethodGet, "/v1/drivers/me/offer", "", nil, &o)
	return o, status == http.StatusOK, err
}

func (s *Session) Accept(ctx context.Context, offerID, key string) (Ride, error) {
	var r Ride
	_, err := s.Do(ctx, http.MethodPost, "/v1/offers/"+offerID+"/accept", key, nil, &r)
	return r, err
}

func (s *Session) Decline(ctx context.Context, offerID, key string) error {
	_, err := s.Do(ctx, http.MethodPost, "/v1/offers/"+offerID+"/decline", key, nil, nil)
	return err
}

// Replay sends up to 100 updates recorded offline, marked replay: true.
func (s *Session) Replay(ctx context.Context, updates []LocationUpdate) (LocationResult, error) {
	var r LocationResult
	_, err := s.Do(ctx, http.MethodPost, "/v1/drivers/me/location", "", map[string]any{"updates": updates}, &r)
	return r, err
}

func (s *Session) Ticket(ctx context.Context) (Ticket, error) {
	var t Ticket
	_, err := s.Do(ctx, http.MethodPost, "/v1/realtime/tickets", "", nil, &t)
	return t, err
}

func (s *Session) Invariants(ctx context.Context) (InvariantReport, error) {
	var r InvariantReport
	_, err := s.Do(ctx, http.MethodGet, "/v1/ops/invariants", "", nil, &r)
	return r, err
}
