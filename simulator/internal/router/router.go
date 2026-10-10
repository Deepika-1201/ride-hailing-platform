// Package router finds the roads agents drive along (ADR-021): OSRM, routes recorded by an earlier run, or straight
// lines.
package router

import (
	"context"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
)

// Route is the way from one point to another; Points has at least the two ends.
type Route struct {
	Points    []geo.Point `json:"points"`
	DistanceM float64     `json:"distance_m"`
	DurationS float64     `json:"duration_s"`
}

// Router answers routes; implementations are safe for concurrent use.
type Router interface {
	Route(ctx context.Context, from, to geo.Point) (Route, error)
}
