package router

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
)

// OSRM asks an OSRM server's route service (the routing Compose profile) for the fastest way by car.
type OSRM struct {
	base   string
	client *http.Client
}

// NewOSRM takes the server's base URL, such as http://localhost:5000.
func NewOSRM(base string) *OSRM {
	return &OSRM{base: strings.TrimRight(base, "/"), client: &http.Client{Timeout: 5 * time.Second}}
}

type osrmAnswer struct {
	Code   string `json:"code"`
	Routes []struct {
		Distance float64 `json:"distance"`
		Duration float64 `json:"duration"`
		Geometry struct {
			Coordinates [][2]float64 `json:"coordinates"`
		} `json:"geometry"`
	} `json:"routes"`
}

func (o *OSRM) Route(ctx context.Context, from, to geo.Point) (Route, error) {
	u := fmt.Sprintf("%s/route/v1/driving/%f,%f;%f,%f?%s", o.base, from.Lon, from.Lat, to.Lon, to.Lat,
		url.Values{"overview": {"full"}, "geometries": {"geojson"}}.Encode())
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return Route{}, err
	}
	resp, err := o.client.Do(req)
	if err != nil {
		return Route{}, fmt.Errorf("osrm: %w", err)
	}
	defer resp.Body.Close()
	var answer osrmAnswer
	if err := json.NewDecoder(resp.Body).Decode(&answer); err != nil {
		return Route{}, fmt.Errorf("osrm: status %d: %w", resp.StatusCode, err)
	}
	if answer.Code != "Ok" || len(answer.Routes) == 0 {
		return Route{}, fmt.Errorf("osrm: no route (%s)", answer.Code)
	}
	best := answer.Routes[0]
	points := make([]geo.Point, 0, len(best.Geometry.Coordinates)+2)
	points = append(points, from)
	for _, c := range best.Geometry.Coordinates {
		points = append(points, geo.Point{Lat: c[1], Lon: c[0]})
	}
	// OSRM starts and ends on the nearest road; the agent starts and ends where it is asked to.
	points = append(points, to)
	return Route{Points: points, DistanceM: best.Distance, DurationS: best.Duration}, nil
}
