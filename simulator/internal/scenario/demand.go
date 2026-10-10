package scenario

import (
	"bufio"
	"fmt"
	"math"
	"math/rand/v2"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
	"github.com/uber/h3-go/v4"
)

// ZoneResolution is the H3 resolution of demand zones, as in pricing (ADR-012).
const ZoneResolution = 7

// zoneRadiusM keeps sampled points inside a resolution-7 cell, whose inner radius is about 1.2 km.
const zoneRadiusM = 1100

const slot = 15 * time.Minute

// Trip is one rider's request: when, from where to where, and in which category.
type Trip struct {
	At       time.Duration
	Pickup   geo.Point
	Dropoff  geo.Point
	Category string
	// Source is the zone or area the demand came from.
	Source string
}

type weighted struct {
	key    string
	weight float64
}

// Model is a scenario's demand: Poisson arrivals per zone and 15-minute slot (ADR-021).
type Model struct {
	s       *Scenario
	centers map[string]geo.Point
	// hours[h] lists the zones with demand in local hour h, and total their weight.
	hours  [24][]weighted
	totals [24]float64
	od     map[string][]weighted
}

// NewModel reads the scenario's zone weights and destinations.
func NewModel(s *Scenario) (*Model, error) {
	m := &Model{s: s, centers: map[string]geo.Point{}, od: map[string][]weighted{}}
	err := readCSV(s.Path(s.Demand.ZoneWeights), 3, func(f []string) error {
		hour, err := strconv.Atoi(f[1])
		if err != nil || hour < 0 || hour > 23 {
			return fmt.Errorf("hour %q", f[1])
		}
		weight, err := m.zone(f[0], f[2])
		if err != nil || weight == 0 {
			return err
		}
		m.hours[hour] = append(m.hours[hour], weighted{f[0], weight})
		m.totals[hour] += weight
		return nil
	})
	if err != nil {
		return nil, err
	}
	if s.Demand.Destinations != "" {
		err = readCSV(s.Path(s.Demand.Destinations), 3, func(f []string) error {
			if _, err := m.zone(f[0], "0"); err != nil {
				return err
			}
			weight, err := m.zone(f[1], f[2])
			if err == nil && weight > 0 {
				m.od[f[0]] = append(m.od[f[0]], weighted{f[1], weight})
			}
			return err
		})
		if err != nil {
			return nil, err
		}
	}
	for i, e := range s.Demand.Events {
		if _, _, err := m.place(e.Zone); err != nil {
			return nil, fmt.Errorf("demand.events[%d]: %w", i, err)
		}
	}
	for at := time.Duration(0); at < s.Duration; at += slot {
		if m.totals[m.hourAt(at)] == 0 {
			return nil, fmt.Errorf("%s: no demand in hour %d", s.Demand.ZoneWeights, m.hourAt(at))
		}
	}
	return m, nil
}

// zone checks a cell and remembers its centre, answering the weight.
func (m *Model) zone(cell, weight string) (float64, error) {
	w, err := strconv.ParseFloat(weight, 64)
	if err != nil || w < 0 {
		return 0, fmt.Errorf("weight %q", weight)
	}
	if _, ok := m.centers[cell]; ok {
		return w, nil
	}
	c := h3.CellFromString(cell)
	if !c.IsValid() || c.Resolution() != ZoneResolution {
		return 0, fmt.Errorf("%q is not an H3 resolution-%d cell", cell, ZoneResolution)
	}
	ll, err := c.LatLng()
	if err != nil {
		return 0, err
	}
	m.centers[cell] = geo.Point{Lat: ll.Lat, Lon: ll.Lng}
	return w, nil
}

// place is a zone's or an area's centre and radius.
func (m *Model) place(zone string) (geo.Point, float64, error) {
	if name, ok := strings.CutPrefix(zone, "area:"); ok {
		area, ok := m.s.Demand.Areas[name]
		if !ok {
			return geo.Point{}, 0, fmt.Errorf("no area %q in demand.areas", name)
		}
		return geo.Point{Lat: area.Lat, Lon: area.Lon}, area.RadiusM, nil
	}
	if _, err := m.zone(zone, "0"); err != nil {
		return geo.Point{}, 0, err
	}
	return m.centers[zone], zoneRadiusM, nil
}

// Trips draws the run's requests in time order. The same seed draws the same trips.
func (m *Model) Trips(seed uint64) []Trip {
	r := rand.New(rand.NewPCG(seed, 0x7269646573))
	var trips []Trip
	for start := time.Duration(0); start < m.s.Duration; start += slot {
		end := min(start+slot, m.s.Duration)
		hour := m.hourAt(start)
		for _, z := range m.hours[hour] {
			perHour := m.s.Demand.BasePerHour * z.weight / m.totals[hour]
			trips = m.arrivals(r, trips, start, end, perHour, z.key)
		}
	}
	for _, e := range m.s.Demand.Events {
		start, end := e.Start, min(e.Start+e.Duration, m.s.Duration)
		for at := start; at < end; at += slot {
			// requirements §7: the event's zone gets multiplier × its normal demand, taken as at least the average
			// zone's, so an event in a quiet place is still a hotspot.
			hour := m.hourAt(at)
			normal := m.s.Demand.BasePerHour / float64(len(m.hours[hour]))
			for _, z := range m.hours[hour] {
				if z.key == e.Zone {
					normal = max(normal, m.s.Demand.BasePerHour*z.weight/m.totals[hour])
				}
			}
			trips = m.arrivals(r, trips, at, min(at+slot, end), (e.Multiplier-1)*normal, e.Zone)
		}
	}
	sort.SliceStable(trips, func(i, j int) bool { return trips[i].At < trips[j].At })
	return trips
}

// arrivals adds a Poisson process's arrivals between start and end, by exponential gaps.
func (m *Model) arrivals(r *rand.Rand, trips []Trip, start, end time.Duration, perHour float64,
	source string) []Trip {
	if perHour <= 0 {
		return trips
	}
	mean := float64(time.Hour) / perHour
	for at := start + time.Duration(r.ExpFloat64()*mean); at < end; at += time.Duration(r.ExpFloat64() * mean) {
		center, radius, _ := m.place(source)
		pickup := Around(r, center, radius)
		trips = append(trips, Trip{At: at, Pickup: pickup, Dropoff: m.destination(r, source, pickup, at),
			Category: m.category(r), Source: source})
	}
	return trips
}

// destination picks a drop-off by the origin's destination weights, or by the hour's demand, at least the minimum
// trip length away.
func (m *Model) destination(r *rand.Rand, source string, pickup geo.Point, at time.Duration) geo.Point {
	choices := m.od[source]
	if len(choices) == 0 {
		choices = m.hours[m.hourAt(at)]
	}
	var point geo.Point
	for range 20 {
		point = Around(r, m.centers[pick(r, choices)], zoneRadiusM)
		d := geo.DistanceM(pickup, point)
		if d >= m.s.Demand.MinTripM && (m.s.Demand.MaxTripM == 0 || d <= m.s.Demand.MaxTripM) {
			break
		}
	}
	return point
}

func (m *Model) category(r *rand.Rand) string {
	total := 0.0
	for _, c := range Categories {
		total += m.s.Demand.Categories[c]
	}
	x := r.Float64() * total
	for _, c := range Categories {
		if x < m.s.Demand.Categories[c] {
			return c
		}
		x -= m.s.Demand.Categories[c]
	}
	return Categories[len(Categories)-1]
}

// Hotspot picks a point in a zone weighted by demand at the given time into the run: where drivers start and drift.
// Past the hours the file covers, the run's first hour stands in.
func (m *Model) Hotspot(r *rand.Rand, at time.Duration) geo.Point {
	hour := m.hourAt(at)
	if len(m.hours[hour]) == 0 {
		hour = m.s.Demand.StartHour
	}
	return Around(r, m.centers[pick(r, m.hours[hour])], zoneRadiusM)
}

func (m *Model) hourAt(at time.Duration) int {
	return (m.s.Demand.StartHour + int(at/time.Hour)) % 24
}

func pick(r *rand.Rand, choices []weighted) string {
	total := 0.0
	for _, c := range choices {
		total += c.weight
	}
	x := r.Float64() * total
	for _, c := range choices {
		if x < c.weight {
			return c.key
		}
		x -= c.weight
	}
	return choices[len(choices)-1].key
}

// Around is a point uniformly distributed within radius metres of a centre.
func Around(r *rand.Rand, center geo.Point, radiusM float64) geo.Point {
	d := radiusM * math.Sqrt(r.Float64())
	angle := 2 * math.Pi * r.Float64()
	return geo.Offset(center, d*math.Cos(angle), d*math.Sin(angle))
}

// readCSV calls row for each line of n fields, skipping blank lines, # comments and a header.
func readCSV(path string, n int, row func([]string) error) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	started := false
	for line := 1; scanner.Scan(); line++ {
		text := strings.TrimSpace(scanner.Text())
		if text == "" || strings.HasPrefix(text, "#") {
			continue
		}
		fields := strings.Split(text, ",")
		if len(fields) != n {
			return fmt.Errorf("%s:%d: want %d fields", path, line, n)
		}
		for i := range fields {
			fields[i] = strings.TrimSpace(fields[i])
		}
		if !started {
			started = true
			if !h3.CellFromString(fields[0]).IsValid() {
				continue
			}
		}
		if err := row(fields); err != nil {
			return fmt.Errorf("%s:%d: %w", path, line, err)
		}
	}
	return scanner.Err()
}
