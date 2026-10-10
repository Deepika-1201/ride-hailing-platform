package geo

import (
	"math"
	"testing"
)

func TestDistanceIsHaversineOnTheServersRadius(t *testing.T) {
	from := Point{12.97, 77.6}
	for _, to := range []Point{Offset(from, 1000, 0), Offset(from, 0, 1000), Offset(from, -1000, 0)} {
		if got := DistanceM(from, to); math.Abs(got-1000) > 0.01 {
			t.Errorf("distance to %v = %.4f m, want 1,000 m", to, got)
		}
	}
	if DistanceM(from, from) != 0 {
		t.Fatal("a point is at no distance from itself")
	}
}

func TestHeadingRunsClockwiseFromNorth(t *testing.T) {
	from := Point{12.97, 77.6}
	cases := map[string]struct {
		to   Point
		want float64
	}{
		"north": {Offset(from, 1000, 0), 0},
		"east":  {Offset(from, 0, 1000), 90},
		"south": {Offset(from, -1000, 0), 180},
		"west":  {Offset(from, 0, -1000), 270},
	}
	for name, c := range cases {
		if got := HeadingDeg(from, c.to); math.Abs(got-c.want) > 0.1 {
			t.Errorf("%s: heading %.2f, want %.0f", name, got, c.want)
		}
	}
}

func TestAPathIsWalkedByDistanceAndClampedAtItsEnds(t *testing.T) {
	start := Point{12.97, 77.6}
	corner := Offset(start, 300, 0)
	end := Offset(corner, 0, 400)
	path := NewPath([]Point{start, corner, end})

	if math.Abs(path.LengthM()-700) > 0.5 {
		t.Fatalf("length %.2f, want 700", path.LengthM())
	}
	at, heading := path.At(450)
	if d := DistanceM(at, Offset(corner, 0, 150)); d > 0.5 {
		t.Errorf("450 m along is %.2f m off", d)
	}
	if math.Abs(heading-90) > 0.5 {
		t.Errorf("heading on the second leg %.2f, want 90", heading)
	}
	if at, _ := path.At(-5); at != start {
		t.Errorf("before the start: %v", at)
	}
	if at, _ := path.At(10_000); at != end {
		t.Errorf("past the end: %v", at)
	}
	if at, _ := NewPath([]Point{start}).At(5); at != start {
		t.Errorf("a one-point path stays put: %v", at)
	}
}
