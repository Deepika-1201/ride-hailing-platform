// Package geo has the little geometry the simulator needs: distances on the sphere and positions along a path.
package geo

import "math"

// EarthRadiusM is the mean radius the server measures with (LLD §9.4).
const EarthRadiusM = 6_371_008.8

// Point is a WGS 84 position.
type Point struct {
	Lat float64 `json:"lat"`
	Lon float64 `json:"lon"`
}

// DistanceM is the haversine distance between two points.
func DistanceM(a, b Point) float64 {
	la1, la2 := rad(a.Lat), rad(b.Lat)
	dLat, dLon := la2-la1, rad(b.Lon-a.Lon)
	h := math.Pow(math.Sin(dLat/2), 2) + math.Cos(la1)*math.Cos(la2)*math.Pow(math.Sin(dLon/2), 2)
	return 2 * EarthRadiusM * math.Asin(math.Min(1, math.Sqrt(h)))
}

// HeadingDeg is the initial bearing from a to b, in [0, 360).
func HeadingDeg(a, b Point) float64 {
	la1, la2 := rad(a.Lat), rad(b.Lat)
	dLon := rad(b.Lon - a.Lon)
	y := math.Sin(dLon) * math.Cos(la2)
	x := math.Cos(la1)*math.Sin(la2) - math.Sin(la1)*math.Cos(la2)*math.Cos(dLon)
	deg := math.Mod(math.Atan2(y, x)*180/math.Pi+360, 360)
	if deg >= 360 {
		return 0
	}
	return deg
}

// Between is the point the fraction f of the way from a to b, interpolated linearly, which is close enough for the
// few hundred metres between route vertices.
func Between(a, b Point, f float64) Point {
	return Point{Lat: a.Lat + (b.Lat-a.Lat)*f, Lon: a.Lon + (b.Lon-a.Lon)*f}
}

// Offset moves a point by metres north and east.
func Offset(p Point, northM, eastM float64) Point {
	dLat := northM / EarthRadiusM * 180 / math.Pi
	dLon := eastM / (EarthRadiusM * math.Cos(rad(p.Lat))) * 180 / math.Pi
	return Point{Lat: p.Lat + dLat, Lon: p.Lon + dLon}
}

// Path is a polyline with the cumulative distance to each vertex.
type Path struct {
	points []Point
	cum    []float64
}

// NewPath needs at least one point; a single point is a path of length zero.
func NewPath(points []Point) Path {
	cum := make([]float64, len(points))
	for i := 1; i < len(points); i++ {
		cum[i] = cum[i-1] + DistanceM(points[i-1], points[i])
	}
	return Path{points: points, cum: cum}
}

// LengthM is the path's length.
func (p Path) LengthM() float64 {
	return p.cum[len(p.cum)-1]
}

// At is the position and heading after travelling d metres from the start, clamped to the path's ends.
func (p Path) At(d float64) (Point, float64) {
	last := len(p.points) - 1
	if last == 0 {
		return p.points[0], 0
	}
	if d <= 0 {
		return p.points[0], HeadingDeg(p.points[0], p.points[1])
	}
	if d >= p.cum[last] {
		return p.points[last], HeadingDeg(p.points[last-1], p.points[last])
	}
	i := 1
	for p.cum[i] < d {
		i++
	}
	segment := p.cum[i] - p.cum[i-1]
	f := 0.0
	if segment > 0 {
		f = (d - p.cum[i-1]) / segment
	}
	return Between(p.points[i-1], p.points[i], f), HeadingDeg(p.points[i-1], p.points[i])
}

func rad(deg float64) float64 {
	return deg * math.Pi / 180
}
