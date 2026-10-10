package router

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"sync"
	"sync/atomic"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
)

// Key identifies a route by its ends rounded to 1e-5 degrees (about a metre), so a replay finds what a run recorded.
func Key(from, to geo.Point) string {
	return fmt.Sprintf("%.5f,%.5f>%.5f,%.5f", from.Lat, from.Lon, to.Lat, to.Lon)
}

type recordedRoute struct {
	Key string `json:"key"`
	Route
}

// Recorder remembers every route its router answers, for Save to write.
type Recorder struct {
	next   Router
	mu     sync.Mutex
	routes map[string]Route
}

func NewRecorder(next Router) *Recorder {
	return &Recorder{next: next, routes: map[string]Route{}}
}

func (r *Recorder) Route(ctx context.Context, from, to geo.Point) (Route, error) {
	route, err := r.next.Route(ctx, from, to)
	if err == nil {
		r.mu.Lock()
		r.routes[Key(from, to)] = route
		r.mu.Unlock()
	}
	return route, err
}

// Save writes the recorded routes as JSON, ordered by key so recordings diff well.
func (r *Recorder) Save(path string) error {
	r.mu.Lock()
	list := make([]recordedRoute, 0, len(r.routes))
	for key, route := range r.routes {
		list = append(list, recordedRoute{Key: key, Route: route})
	}
	r.mu.Unlock()
	sort.Slice(list, func(i, j int) bool { return list[i].Key < list[j].Key })
	data, err := json.MarshalIndent(list, "", " ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0o644)
}

// Recorded replays a recording. A route it lacks, because the server matched differently this time, comes from the
// fallback and is counted.
type Recorded struct {
	routes   map[string]Route
	fallback Router
	misses   atomic.Int64
}

func LoadRecorded(path string, fallback Router) (*Recorded, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var list []recordedRoute
	if err := json.Unmarshal(data, &list); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	routes := make(map[string]Route, len(list))
	for _, r := range list {
		routes[r.Key] = r.Route
	}
	return &Recorded{routes: routes, fallback: fallback}, nil
}

func (r *Recorded) Route(ctx context.Context, from, to geo.Point) (Route, error) {
	if route, ok := r.routes[Key(from, to)]; ok {
		return route, nil
	}
	r.misses.Add(1)
	return r.fallback.Route(ctx, from, to)
}

// Misses is how many routes came from the fallback.
func (r *Recorded) Misses() int64 {
	return r.misses.Load()
}
