package router

import (
	"context"
	"math"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
)

var (
	home = geo.Point{Lat: 12.97571, Lon: 77.60502}
	work = geo.Point{Lat: 12.93524, Lon: 77.62448}
)

func TestAStraightRouteTakesItsDistanceAtItsSpeed(t *testing.T) {
	route, err := Straight{SpeedMps: 10}.Route(context.Background(), home, work)
	if err != nil {
		t.Fatal(err)
	}
	if len(route.Points) != 2 || route.Points[0] != home || route.Points[1] != work {
		t.Fatalf("points %v", route.Points)
	}
	if math.Abs(route.DurationS-route.DistanceM/10) > 1e-9 {
		t.Fatalf("%.1f s for %.1f m", route.DurationS, route.DistanceM)
	}
}

func TestOSRMsRouteRunsFromTheAskedPointsAlongTheRoad(t *testing.T) {
	var asked string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = r.URL.String()
		w.Write([]byte(`{"code": "Ok", "routes": [{"distance": 5400.5, "duration": 812.3,
			"geometry": {"coordinates": [[77.6051, 12.9757], [77.615, 12.95], [77.6244, 12.9353]]}}]}`))
	}))
	defer server.Close()

	route, err := NewOSRM(server.URL).Route(context.Background(), home, work)
	if err != nil {
		t.Fatal(err)
	}
	if asked != "/route/v1/driving/77.605020,12.975710;77.624480,12.935240?geometries=geojson&overview=full" {
		t.Errorf("asked %s", asked)
	}
	if len(route.Points) != 5 || route.Points[0] != home || route.Points[4] != work ||
		route.Points[2] != (geo.Point{Lat: 12.95, Lon: 77.615}) {
		t.Errorf("points %v", route.Points)
	}
	if route.DistanceM != 5400.5 || route.DurationS != 812.3 {
		t.Errorf("distance %.1f, duration %.1f", route.DistanceM, route.DurationS)
	}
}

func TestOSRMWithoutARouteIsAnError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"code": "NoRoute", "routes": []}`))
	}))
	defer server.Close()

	if _, err := NewOSRM(server.URL).Route(context.Background(), home, work); err == nil {
		t.Fatal("want an error")
	}
}

func TestARecordedRunReplaysItsRoutesAndCountsWhatItLacks(t *testing.T) {
	ctx := context.Background()
	slow := Straight{SpeedMps: 1}
	recorder := NewRecorder(slow)
	recordedRoute, _ := recorder.Route(ctx, home, work)
	path := filepath.Join(t.TempDir(), "routes.json")
	if err := recorder.Save(path); err != nil {
		t.Fatal(err)
	}

	replay, err := LoadRecorded(path, Straight{SpeedMps: 100})
	if err != nil {
		t.Fatal(err)
	}
	again, _ := replay.Route(ctx, geo.Point{Lat: home.Lat + 1e-7, Lon: home.Lon}, work)
	if again.DurationS != recordedRoute.DurationS {
		t.Errorf("the recorded route at %.1f s, got %.1f s", recordedRoute.DurationS, again.DurationS)
	}
	if replay.Misses() != 0 {
		t.Errorf("misses %d", replay.Misses())
	}
	elsewhere, _ := replay.Route(ctx, work, home)
	if replay.Misses() != 1 || math.Abs(elsewhere.DurationS-elsewhere.DistanceM/100) > 1e-9 {
		t.Errorf("an unrecorded route comes from the fallback: misses %d, %.1f s", replay.Misses(), elsewhere.DurationS)
	}
}

func TestCIRecordingContainsRoadGeometryAndReplaysWithoutFallback(t *testing.T) {
	replay, err := LoadRecorded(filepath.Join("..", "..", "testdata", "routes", "ci.json"), DefaultStraight)
	if err != nil {
		t.Fatal(err)
	}
	if len(replay.routes) == 0 {
		t.Fatal("the CI route recording is empty")
	}
	roadRoutes := 0
	for key, recorded := range replay.routes {
		if len(recorded.Points) < 2 {
			t.Fatalf("route %s has fewer than two points", key)
		}
		from, to := recorded.Points[0], recorded.Points[len(recorded.Points)-1]
		if Key(from, to) != key {
			t.Fatalf("route %s does not match its recorded endpoints", key)
		}
		if len(recorded.Points) > 2 && recorded.DistanceM > 0 {
			roadRoutes++
		}
		got, err := replay.Route(context.Background(), from, to)
		if err != nil || got.DistanceM != recorded.DistanceM || got.DurationS != recorded.DurationS {
			t.Fatalf("route %s was not replayed: %v", key, err)
		}
	}
	if roadRoutes == 0 || replay.Misses() != 0 {
		t.Fatalf("recorded road routes %d, fallback routes %d", roadRoutes, replay.Misses())
	}
}
