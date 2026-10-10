// Command zones writes the Bengaluru demand files under scenarios/zones from a table of hubs: each hub's H3
// resolution-7 cell and its six neighbours, weighted by hour for the hub's kind, and destinations by a gravity model.
//
//	go run ./cmd/zones -out scenarios/zones
package main

import (
	"flag"
	"fmt"
	"log"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/Deepika-1201/ride-hailing-platform/simulator/internal/geo"
	"github.com/uber/h3-go/v4"
)

type hub struct {
	name     string
	lat, lon float64
	kind     string
	size     float64
}

// Pickups by local hour for each kind of place: homes empty in the morning, offices in the evening.
var profiles = map[string][24]float64{
	"residential": {.15, .08, .05, .05, .08, .2, .5, 1, 1.6, 1.5, .9, .6, .5, .5, .5, .55, .6, .7, .8, .8, .7, .6, .45, .3},
	"office":      {.1, .05, .03, .03, .05, .1, .2, .3, .4, .5, .5, .6, .8, .7, .6, .7, .9, 1.4, 1.8, 1.5, 1, .6, .35, .2},
	"mixed":       {.3, .2, .1, .08, .1, .2, .35, .6, .8, .9, .9, .9, 1, 1, .9, .9, 1, 1.2, 1.4, 1.5, 1.4, 1.2, .9, .6},
	"transit":     {.6, .5, .4, .5, .8, 1, 1, .9, .9, .8, .7, .7, .7, .7, .7, .8, .9, 1, 1.1, 1.1, 1, .9, .8, .7},
}

// How strongly each kind draws trips.
var attraction = map[string]float64{"residential": 1, "office": 1.3, "mixed": 1.2, "transit": 0.8}

var hubs = []hub{
	{"MG Road", 12.9756, 77.6050, "office", 1},
	{"Koramangala", 12.9352, 77.6245, "mixed", 1},
	{"Indiranagar", 12.9719, 77.6412, "mixed", 0.9},
	{"Whitefield", 12.9868, 77.7360, "office", 0.9},
	{"Electronic City", 12.8452, 77.6602, "office", 0.8},
	{"Marathahalli", 12.9569, 77.7011, "office", 0.8},
	{"Bellandur", 12.9260, 77.6762, "office", 0.7},
	{"HSR Layout", 12.9116, 77.6474, "residential", 0.8},
	{"Jayanagar", 12.9250, 77.5938, "residential", 0.7},
	{"Malleshwaram", 13.0035, 77.5709, "residential", 0.6},
	{"Hebbal", 13.0358, 77.5970, "mixed", 0.6},
	{"Yelahanka", 13.1007, 77.5963, "residential", 0.4},
	{"Airport", 13.1986, 77.7066, "transit", 0.8},
	{"KSR station", 12.9781, 77.5697, "transit", 0.7},
	{"Manyata", 13.0450, 77.6200, "office", 0.7},
	{"BTM Layout", 12.9166, 77.6101, "residential", 0.7},
	{"Rajajinagar", 12.9915, 77.5543, "residential", 0.5},
	{"Banashankari", 12.9255, 77.5468, "residential", 0.5},
	{"JP Nagar", 12.9063, 77.5857, "residential", 0.6},
	{"Sarjapur Road", 12.9100, 77.6860, "residential", 0.6},
	{"KR Puram", 13.0076, 77.6955, "residential", 0.5},
	{"Bannerghatta Road", 12.8880, 77.5970, "residential", 0.5},
}

// The compact centre CI uses: short pickups and short trips.
var central = map[string]bool{"MG Road": true, "Koramangala": true, "Indiranagar": true}

type zone struct {
	cell   string
	hub    hub
	weight float64
	center geo.Point
}

func main() {
	out := flag.String("out", "scenarios/zones", "directory to write to")
	flag.Parse()
	all := zones(func(hub) bool { return true })
	write(filepath.Join(*out, "blr-weekday.csv"), "cell,hour,weight", weights(all))
	write(filepath.Join(*out, "blr-od.csv"), "origin,destination,weight", destinations(all))
	write(filepath.Join(*out, "blr-central.csv"), "cell,hour,weight",
		weights(zones(func(h hub) bool { return central[h.name] })))
}

// zones are each hub's cell at full weight and its ring at a third; a cell two hubs share keeps the larger weight.
func zones(keep func(hub) bool) []zone {
	byCell := map[string]zone{}
	for _, h := range hubs {
		if !keep(h) {
			continue
		}
		center, err := h3.LatLngToCell(h3.NewLatLng(h.lat, h.lon), 7)
		check(err)
		disk, err := center.GridDisk(1)
		check(err)
		for _, c := range disk {
			w := h.size
			if c != center {
				w *= 0.35
			}
			if z, ok := byCell[c.String()]; !ok || z.weight < w {
				ll, err := c.LatLng()
				check(err)
				byCell[c.String()] = zone{c.String(), h, w, geo.Point{Lat: ll.Lat, Lon: ll.Lng}}
			}
		}
	}
	list := make([]zone, 0, len(byCell))
	for _, z := range byCell {
		list = append(list, z)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].cell < list[j].cell })
	return list
}

func weights(zones []zone) []string {
	var rows []string
	for _, z := range zones {
		for hour, factor := range profiles[z.hub.kind] {
			rows = append(rows, fmt.Sprintf("%s,%d,%.3f", z.cell, hour, z.weight*factor))
		}
	}
	return rows
}

// destinations keeps each zone's twelve likeliest destinations: attraction over the square of distance, softened.
func destinations(zones []zone) []string {
	var rows []string
	for _, from := range zones {
		var candidates []zone
		for _, to := range zones {
			if to.hub.name != from.hub.name {
				km := geo.DistanceM(from.center, to.center) / 1000
				to.weight = to.weight * attraction[to.hub.kind] / math.Pow(1+km/6, 2)
				candidates = append(candidates, to)
			}
		}
		sort.Slice(candidates, func(i, j int) bool { return candidates[i].weight > candidates[j].weight })
		for _, to := range candidates[:min(12, len(candidates))] {
			rows = append(rows, fmt.Sprintf("%s,%s,%.4f", from.cell, to.cell, to.weight))
		}
	}
	return rows
}

func write(path, header string, rows []string) {
	content := "# Generated by go run ./cmd/zones; edit the hub table there, not this file.\n" + header + "\n" +
		strings.Join(rows, "\n") + "\n"
	check(os.MkdirAll(filepath.Dir(path), 0o755))
	check(os.WriteFile(path, []byte(content), 0o644))
	log.Printf("%s: %d rows", path, len(rows))
}

func check(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
