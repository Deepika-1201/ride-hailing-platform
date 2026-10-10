package router

import (
	"context"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
)

// Straight drives in a straight line at a fixed speed: for smoke tests, and for routes a recording lacks.
type Straight struct {
	SpeedMps float64
}

// DefaultStraight drives at 25 km/h, Bengaluru's typical daytime speed in the mock router (ADR-013).
var DefaultStraight = Straight{SpeedMps: 25 / 3.6}

func (s Straight) Route(_ context.Context, from, to geo.Point) (Route, error) {
	d := geo.DistanceM(from, to)
	return Route{Points: []geo.Point{from, to}, DistanceM: d, DurationS: d / s.SpeedMps}, nil
}
