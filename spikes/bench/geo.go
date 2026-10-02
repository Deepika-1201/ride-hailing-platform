package main

// Spike S-1: which store answers "nearest available drivers" fastest while
// positions change thousands of times a second?
//   redis-geo        Valkey GEO set per category, holding available drivers
//   redis-h3         Valkey set per H3 cell and category, positions in a hash
//   postgis          PostGIS table, one row per driver, GiST index
//   postgis-unlogged the same table without WAL and without synchronous commit

import (
	"context"
	"flag"
	"fmt"
	"math"
	"math/rand/v2"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"github.com/uber/h3-go/v4"
)

const (
	metersPerDegLat = 111_320.0
	pingEvery       = 4 * time.Second
	freshFor        = 30 * time.Second
	cityTag         = "{blr}" // hash tag: every key of a city lands on the same shard
)

var categoryWeights = []float64{0.40, 0.35, 0.15, 0.10} // auto, mini, sedan, XL

// Bengaluru supply and demand centres; the last entry spreads the rest across the city.
var hotspots = []struct{ lat, lon, sigmaKm, weight float64 }{
	{12.9757, 77.6050, 2.0, 0.22}, // CBD, MG Road
	{12.9352, 77.6245, 1.8, 0.14}, // Koramangala
	{12.9698, 77.7500, 2.2, 0.12}, // Whitefield
	{12.8452, 77.6600, 1.8, 0.10}, // Electronic City
	{13.0450, 77.6200, 1.8, 0.10}, // Manyata, Hebbal
	{12.9767, 77.5713, 1.5, 0.08}, // Majestic
	{13.1989, 77.7068, 1.2, 0.06}, // Airport
	{12.9716, 77.5946, 7.0, 0.18}, // background
}

type driver struct {
	id             int
	cat            int
	avail          bool
	lat, lon       float64
	heading, speed float64
	seq            int64
	cell           h3.Cell
}

type result struct {
	id     int
	distM  float64
	seenMs int64
}

type liveIndex interface {
	setup(ctx context.Context, ds []*driver) error
	// update stores a new position; prevCell and prevAvail describe the last stored state.
	update(ctx context.Context, d *driver, prevCell h3.Cell, prevAvail bool, nowMs int64) error
	query(ctx context.Context, lat, lon float64, cat int, nowMs int64) ([]result, error)
	sweep(ctx context.Context, nowMs int64) (int, error)
	stats(ctx context.Context) (map[string]float64, error)
	report(before, after map[string]float64, elapsed time.Duration)
}

type geoParams struct {
	radiusM float64
	k       int
	h3Res   int
}

func runGeo(args []string) error {
	fs := flag.NewFlagSet("geo", flag.ExitOnError)
	approach := fs.String("approach", "redis-geo", "redis-geo | redis-h3 | postgis | postgis-unlogged")
	drivers := fs.Int("drivers", 2000, "online drivers (each sends an update every 4 s)")
	queryRate := fs.Float64("query-rate", 15, "nearest-driver queries per second")
	updateWorkers := fs.Int("update-workers", 16, "concurrent update senders")
	queryWorkers := fs.Int("query-workers", 8, "concurrent query senders")
	warmup := fs.Duration("warmup", 15*time.Second, "warm-up before measuring")
	measure := fs.Duration("measure", 45*time.Second, "measurement window")
	silentPct := fs.Float64("silent-pct", 2, "percent of drivers that go silent when measurement starts")
	radius := fs.Float64("radius-m", 3000, "search radius in metres")
	k := fs.Int("k", 20, "candidates wanted per query")
	res := fs.Int("h3-res", 8, "H3 resolution for redis-h3")
	redisAddr := fs.String("redis", "valkey:6379", "Valkey address")
	pgURL := fs.String("pg", "postgres://spike:spike@postgis:5432/spike", "PostgreSQL URL")
	seed := fs.Uint64("seed", 42, "random seed")
	if err := fs.Parse(args); err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p := geoParams{radiusM: *radius, k: *k, h3Res: *res}
	poolSize := *updateWorkers + *queryWorkers + 4

	var idx liveIndex
	var err error
	switch *approach {
	case "redis-geo", "redis-h3":
		rdb := redis.NewClient(&redis.Options{Addr: *redisAddr, PoolSize: poolSize})
		if *approach == "redis-geo" {
			idx, err = newRedisGeo(ctx, rdb, p)
		} else {
			idx, err = newRedisH3(ctx, rdb, p)
		}
	case "postgis", "postgis-unlogged":
		idx, err = newPostgis(ctx, *pgURL, poolSize, *approach == "postgis-unlogged", p)
	default:
		err = fmt.Errorf("unknown approach %q", *approach)
	}
	if err != nil {
		return err
	}

	rng := rand.New(rand.NewPCG(*seed, 1))
	ds := make([]*driver, *drivers)
	for i := range ds {
		lat, lon := samplePoint(rng)
		ds[i] = &driver{id: i + 1, cat: pickWeighted(rng, categoryWeights), avail: rng.Float64() < 0.4,
			lat: lat, lon: lon, heading: rng.Float64() * 2 * math.Pi, speed: rng.Float64() * 12}
		ds[i].cell, _ = h3.LatLngToCell(h3.NewLatLng(lat, lon), p.h3Res)
	}
	start := time.Now()
	if err := idx.setup(ctx, ds); err != nil {
		return fmt.Errorf("setup: %w", err)
	}
	updateRate := float64(*drivers) / pingEvery.Seconds()
	fmt.Printf("approach=%s drivers=%d update_rate=%.0f/s query_rate=%.0f/s radius=%.0fm k=%d load=%v\n",
		*approach, *drivers, updateRate, *queryRate, p.radiusM, p.k, time.Since(start).Round(time.Millisecond))

	updates, queries := &recorder{}, &recorder{}
	var silenced atomic.Bool
	silentEvery := int(math.Round(100 / *silentPct))
	var wg sync.WaitGroup

	for w := 0; w < *updateWorkers; w++ {
		var own []*driver
		for i := w; i < len(ds); i += *updateWorkers {
			own = append(own, ds[i])
		}
		if len(own) == 0 {
			continue
		}
		wg.Add(1)
		go func(w int, own []*driver) {
			defer wg.Done()
			r := rand.New(rand.NewPCG(*seed, uint64(100+w)))
			wait := pacer(updateRate / float64(*updateWorkers))
			for i := 0; ctx.Err() == nil; i = (i + 1) % len(own) {
				wait()
				d := own[i]
				if silenced.Load() && d.id%silentEvery == 0 {
					continue
				}
				prevCell, prevAvail := d.cell, d.avail
				d.move(r, p.h3Res)
				d.seq++
				t0 := time.Now()
				if err := idx.update(ctx, d, prevCell, prevAvail, t0.UnixMilli()); err != nil {
					if ctx.Err() == nil {
						updates.fail()
					}
					continue
				}
				updates.add(time.Since(t0))
			}
		}(w, own)
	}

	var stale, short, returned atomic.Int64
	for w := 0; w < *queryWorkers; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			r := rand.New(rand.NewPCG(*seed, uint64(1000+w)))
			wait := pacer(*queryRate / float64(*queryWorkers))
			for ctx.Err() == nil {
				wait()
				lat, lon := samplePoint(r)
				t0 := time.Now()
				now := t0.UnixMilli()
				got, err := idx.query(ctx, lat, lon, pickWeighted(r, categoryWeights), now)
				if err != nil {
					if ctx.Err() == nil {
						queries.fail()
					}
					continue
				}
				queries.add(time.Since(t0))
				returned.Add(int64(len(got)))
				if len(got) < p.k {
					short.Add(1)
				}
				for _, g := range got {
					if g.seenMs < now-freshFor.Milliseconds() {
						stale.Add(1)
					}
				}
			}
		}(w)
	}

	var swept atomic.Int64
	wg.Add(1)
	go func() {
		defer wg.Done()
		t := time.NewTicker(5 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				n, err := idx.sweep(ctx, time.Now().UnixMilli())
				if err == nil {
					swept.Add(int64(n))
				}
			}
		}
	}()

	time.Sleep(*warmup)
	updates.reset()
	queries.reset()
	stale.Store(0)
	short.Store(0)
	returned.Store(0)
	before, err := idx.stats(ctx)
	if err != nil {
		return err
	}
	fmt.Println("MEASURE_START")
	silenced.Store(true)
	t0 := time.Now()
	time.Sleep(*measure)
	elapsed := time.Since(t0)
	fmt.Println("MEASURE_END")
	after, err := idx.stats(ctx)
	if err != nil {
		return err
	}
	cancel()
	wg.Wait()

	fmt.Println(updates.summary("update", elapsed))
	fmt.Println(queries.summary("query", elapsed))
	n := len(queries.samples)
	if n == 0 {
		n = 1
	}
	fmt.Printf("%-14s avg_returned=%.1f short_of_k=%.1f%% stale_returned=%d swept=%d\n", "results",
		float64(returned.Load())/float64(n), 100*float64(short.Load())/float64(n), stale.Load(), swept.Load())
	idx.report(before, after, elapsed)
	return nil
}

func pickWeighted(r *rand.Rand, weights []float64) int {
	x := r.Float64()
	for i, w := range weights {
		if x < w {
			return i
		}
		x -= w
	}
	return len(weights) - 1
}

var hotspotWeights = func() []float64 {
	w := make([]float64, len(hotspots))
	for i, h := range hotspots {
		w[i] = h.weight
	}
	return w
}()

func samplePoint(r *rand.Rand) (lat, lon float64) {
	h := hotspots[pickWeighted(r, hotspotWeights)]
	lat = h.lat + r.NormFloat64()*h.sigmaKm*1000/metersPerDegLat
	lon = h.lon + r.NormFloat64()*h.sigmaKm*1000/(metersPerDegLat*math.Cos(h.lat*math.Pi/180))
	return lat, lon
}

// move advances the driver by one update interval and occasionally starts or ends a trip,
// keeping about 40% of drivers available (1,000 s available, 1,500 s busy on average).
func (d *driver) move(r *rand.Rand, res int) {
	d.heading += r.NormFloat64() * 0.35
	d.speed = math.Min(14, math.Max(0, d.speed+r.NormFloat64()*1.5))
	step := d.speed * pingEvery.Seconds()
	d.lat += step * math.Cos(d.heading) / metersPerDegLat
	d.lon += step * math.Sin(d.heading) / (metersPerDegLat * math.Cos(d.lat*math.Pi/180))
	if haversine(d.lat, d.lon, 12.9716, 77.5946) > 30_000 {
		d.heading += math.Pi
	}
	if d.avail {
		d.avail = r.Float64() >= pingEvery.Seconds()/1000
	} else {
		d.avail = r.Float64() < pingEvery.Seconds()/1500
	}
	d.cell, _ = h3.LatLngToCell(h3.NewLatLng(d.lat, d.lon), res)
}

func haversine(lat1, lon1, lat2, lon2 float64) float64 {
	const earthM = 6_371_000.0
	p1, p2 := lat1*math.Pi/180, lat2*math.Pi/180
	dp, dl := p2-p1, (lon2-lon1)*math.Pi/180
	a := math.Sin(dp/2)*math.Sin(dp/2) + math.Cos(p1)*math.Cos(p2)*math.Sin(dl/2)*math.Sin(dl/2)
	return 2 * earthM * math.Asin(math.Sqrt(a))
}

func flag01(b bool) string {
	if b {
		return "1"
	}
	return "0"
}

// ---------------------------------------------------------------- Valkey, shared

type redisBase struct{ rdb *redis.Client }

func driverKey(id int) string { return cityTag + ":drv:" + strconv.Itoa(id) }

const seenKey = cityTag + ":seen"

func (x *redisBase) stats(ctx context.Context) (map[string]float64, error) {
	info, err := x.rdb.Info(ctx, "cpu", "memory", "commandstats").Result()
	if err != nil {
		return nil, err
	}
	out := map[string]float64{}
	for _, line := range strings.Split(info, "\r\n") {
		k, v, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		switch {
		case k == "used_cpu_sys" || k == "used_cpu_user":
			f, _ := strconv.ParseFloat(v, 64)
			out["cpu_s"] += f
		case k == "used_memory":
			f, _ := strconv.ParseFloat(v, 64)
			out["memory_mb"] = f / 1_048_576
		case strings.HasPrefix(k, "cmdstat_"):
			cmd := strings.TrimPrefix(k, "cmdstat_")
			for _, kv := range strings.Split(v, ",") {
				name, val, _ := strings.Cut(kv, "=")
				if name == "calls" || name == "usec" {
					f, _ := strconv.ParseFloat(val, 64)
					out[cmd+"."+name] = f
				}
			}
		}
	}
	return out, nil
}

func (x *redisBase) report(before, after map[string]float64, elapsed time.Duration) {
	fmt.Printf("%-14s cores=%.2f memory=%.1fMB\n", "server",
		(after["cpu_s"]-before["cpu_s"])/elapsed.Seconds(), after["memory_mb"])
	var cmds []string
	for key := range after {
		if cmd, ok := strings.CutSuffix(key, ".calls"); ok && after[key] > before[key] {
			cmds = append(cmds, cmd)
		}
	}
	sort.Strings(cmds)
	for _, cmd := range cmds {
		calls := after[cmd+".calls"] - before[cmd+".calls"]
		usec := after[cmd+".usec"] - before[cmd+".usec"]
		fmt.Printf("  %-12s calls/s=%-9.0f usec/call=%.1f\n", cmd, calls/elapsed.Seconds(), usec/calls)
	}
}

func (x *redisBase) load(ctx context.Context, ds []*driver, add func(redis.Pipeliner, *driver)) error {
	if err := x.rdb.FlushAll(ctx).Err(); err != nil {
		return err
	}
	now := time.Now().UnixMilli()
	for i := 0; i < len(ds); i += 1000 {
		pipe := x.rdb.Pipeline()
		for _, d := range ds[i:min(i+1000, len(ds))] {
			pipe.HSet(ctx, driverKey(d.id), "seq", 0, "ts", now, "lat", d.lat, "lon", d.lon,
				"avail", flag01(d.avail), "cell", strconv.FormatInt(int64(d.cell), 16))
			pipe.ZAdd(ctx, seenKey, redis.Z{Score: float64(now), Member: d.id})
			if d.avail {
				add(pipe, d)
			}
		}
		if _, err := pipe.Exec(ctx); err != nil {
			return err
		}
	}
	return nil
}

// sweepScript drops drivers not heard from since ARGV[1] from the seen set and from every index key.
const sweepScript = `
local stale = redis.call('ZRANGE', KEYS[1], '-inf', '(' .. ARGV[1], 'BYSCORE', 'LIMIT', 0, 1000)
for _, id in ipairs(stale) do
  local h = redis.call('HMGET', ARGV[2] .. id, 'cell')
  for i = 2, #KEYS do redis.call('ZREM', KEYS[i], id) end
  if ARGV[3] ~= '' and h[1] then
    for c = 0, 3 do redis.call('SREM', ARGV[3] .. c .. ':' .. h[1], id) end
  end
end
if #stale > 0 then redis.call('ZREM', KEYS[1], unpack(stale)) end
return #stale`

// ---------------------------------------------------------------- Valkey GEO

type redisGeo struct {
	redisBase
	p                    geoParams
	update_, query_, swp *redis.Script
}

func geoKey(cat int) string { return cityTag + ":geo:" + strconv.Itoa(cat) }

func newRedisGeo(ctx context.Context, rdb *redis.Client, p geoParams) (*redisGeo, error) {
	return &redisGeo{redisBase: redisBase{rdb}, p: p,
		// KEYS: driver hash, GEO key, seen; ARGV: id, seq, ts, lat, lon, avail, wasAvail
		update_: redis.NewScript(`
local cur = redis.call('HGET', KEYS[1], 'seq')
if cur and tonumber(cur) >= tonumber(ARGV[2]) then return 0 end
redis.call('HSET', KEYS[1], 'seq', ARGV[2], 'ts', ARGV[3], 'lat', ARGV[4], 'lon', ARGV[5], 'avail', ARGV[6])
if ARGV[6] == '1' then
  redis.call('GEOADD', KEYS[2], ARGV[5], ARGV[4], ARGV[1])
elseif ARGV[7] == '1' then
  redis.call('ZREM', KEYS[2], ARGV[1])
end
redis.call('ZADD', KEYS[3], ARGV[3], ARGV[1])
return 1`),
		// KEYS: GEO key, seen; ARGV: lon, lat, radius m, count, min ts, k
		query_: redis.NewScript(`
local res = redis.call('GEOSEARCH', KEYS[1], 'FROMLONLAT', ARGV[1], ARGV[2], 'BYRADIUS', ARGV[3], 'm', 'ASC', 'COUNT', ARGV[4], 'WITHDIST')
local out, minTs, want = {}, tonumber(ARGV[5]), tonumber(ARGV[6])
for _, item in ipairs(res) do
  local ts = redis.call('ZSCORE', KEYS[2], item[1])
  if ts and tonumber(ts) >= minTs then
    out[#out + 1] = item[1]; out[#out + 1] = item[2]; out[#out + 1] = ts
    if #out >= want * 3 then break end
  end
end
return out`),
		swp: redis.NewScript(sweepScript),
	}, nil
}

func (x *redisGeo) setup(ctx context.Context, ds []*driver) error {
	return x.load(ctx, ds, func(pipe redis.Pipeliner, d *driver) {
		pipe.GeoAdd(ctx, geoKey(d.cat), &redis.GeoLocation{Name: strconv.Itoa(d.id), Longitude: d.lon, Latitude: d.lat})
	})
}

func (x *redisGeo) update(ctx context.Context, d *driver, _ h3.Cell, prevAvail bool, nowMs int64) error {
	return x.update_.Run(ctx, x.rdb, []string{driverKey(d.id), geoKey(d.cat), seenKey},
		d.id, d.seq, nowMs, d.lat, d.lon, flag01(d.avail), flag01(prevAvail)).Err()
}

func (x *redisGeo) query(ctx context.Context, lat, lon float64, cat int, nowMs int64) ([]result, error) {
	raw, err := x.query_.Run(ctx, x.rdb, []string{geoKey(cat), seenKey},
		lon, lat, x.p.radiusM, 2*x.p.k, nowMs-freshFor.Milliseconds(), x.p.k).StringSlice()
	if err != nil {
		return nil, err
	}
	out := make([]result, 0, len(raw)/3)
	for i := 0; i+2 < len(raw); i += 3 {
		id, _ := strconv.Atoi(raw[i])
		dist, _ := strconv.ParseFloat(raw[i+1], 64)
		seen, _ := strconv.ParseFloat(raw[i+2], 64)
		out = append(out, result{id: id, distM: dist, seenMs: int64(seen)})
	}
	return out, nil
}

func (x *redisGeo) sweep(ctx context.Context, nowMs int64) (int, error) {
	keys := []string{seenKey, geoKey(0), geoKey(1), geoKey(2), geoKey(3)}
	return x.swp.Run(ctx, x.rdb, keys, nowMs-freshFor.Milliseconds(), cityTag+":drv:", "").Int()
}

// ---------------------------------------------------------------- Valkey + H3 cells

type redisH3 struct {
	redisBase
	p                    geoParams
	spacingM             float64
	maxK                 int
	update_, query_, swp *redis.Script
}

func cellKey(cat int, c h3.Cell) string {
	return cityTag + ":cell:" + strconv.Itoa(cat) + ":" + strconv.FormatInt(int64(c), 16)
}

func newRedisH3(ctx context.Context, rdb *redis.Client, p geoParams) (*redisH3, error) {
	// Measure the spacing between neighbouring cell centres in the city instead of trusting averages.
	origin, err := h3.LatLngToCell(h3.NewLatLng(12.9716, 77.5946), p.h3Res)
	if err != nil {
		return nil, err
	}
	ring, err := h3.GridDisk(origin, 1)
	if err != nil {
		return nil, err
	}
	o, _ := h3.CellToLatLng(origin)
	n, _ := h3.CellToLatLng(ring[len(ring)-1])
	spacing := haversine(o.Lat, o.Lng, n.Lat, n.Lng)
	x := &redisH3{redisBase: redisBase{rdb}, p: p, spacingM: spacing,
		// KEYS: driver hash, seen, old cell key, new cell key; ARGV: id, seq, ts, lat, lon, avail, cell, wasAvail
		update_: redis.NewScript(`
local cur = redis.call('HGET', KEYS[1], 'seq')
if cur and tonumber(cur) >= tonumber(ARGV[2]) then return 0 end
redis.call('HSET', KEYS[1], 'seq', ARGV[2], 'ts', ARGV[3], 'lat', ARGV[4], 'lon', ARGV[5], 'avail', ARGV[6], 'cell', ARGV[7])
local moved = KEYS[3] ~= KEYS[4]
if ARGV[8] == '1' and (ARGV[6] == '0' or moved) then redis.call('SREM', KEYS[3], ARGV[1]) end
if ARGV[6] == '1' and (ARGV[8] == '0' or moved) then redis.call('SADD', KEYS[4], ARGV[1]) end
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
return 1`),
		// KEYS: cell keys; ARGV: min ts, driver key prefix. Returns id, lat, lon, ts for fresh members.
		query_: redis.NewScript(`
local out, minTs = {}, tonumber(ARGV[1])
for _, key in ipairs(KEYS) do
  for _, id in ipairs(redis.call('SMEMBERS', key)) do
    local v = redis.call('HMGET', ARGV[2] .. id, 'lat', 'lon', 'ts')
    if v[3] and tonumber(v[3]) >= minTs then
      out[#out + 1] = id; out[#out + 1] = v[1]; out[#out + 1] = v[2]; out[#out + 1] = v[3]
    end
  end
end
return out`),
		swp: redis.NewScript(sweepScript),
	}
	for x.maxK = 1; x.coveredM(x.maxK) < p.radiusM; x.maxK++ {
	}
	return x, nil
}

// coveredM is the radius around any point of the origin cell that grid disk k is guaranteed to contain:
// a hex step can be as short as spacing·√3/2, and the point and the target can each sit a circumradius
// (spacing/√3) away from their cell centres.
func (x *redisH3) coveredM(k int) float64 {
	return x.spacingM * (float64(k)*math.Sqrt(3)/2 - 2/math.Sqrt(3))
}

func (x *redisH3) setup(ctx context.Context, ds []*driver) error {
	fmt.Printf("h3 res=%d neighbour_spacing=%.0fm max_k=%d\n", x.p.h3Res, x.spacingM, x.maxK)
	return x.load(ctx, ds, func(pipe redis.Pipeliner, d *driver) {
		pipe.SAdd(ctx, cellKey(d.cat, d.cell), d.id)
	})
}

func (x *redisH3) update(ctx context.Context, d *driver, prevCell h3.Cell, prevAvail bool, nowMs int64) error {
	return x.update_.Run(ctx, x.rdb,
		[]string{driverKey(d.id), seenKey, cellKey(d.cat, prevCell), cellKey(d.cat, d.cell)},
		d.id, d.seq, nowMs, d.lat, d.lon, flag01(d.avail), strconv.FormatInt(int64(d.cell), 16), flag01(prevAvail)).Err()
}

func (x *redisH3) query(ctx context.Context, lat, lon float64, cat int, nowMs int64) ([]result, error) {
	origin, err := h3.LatLngToCell(h3.NewLatLng(lat, lon), x.p.h3Res)
	if err != nil {
		return nil, err
	}
	fetched := map[h3.Cell]bool{}
	var found []result
	for k := 1; k <= x.maxK; k++ {
		disk, err := h3.GridDisk(origin, k)
		if err != nil {
			return nil, err
		}
		var keys []string
		for _, c := range disk {
			if !fetched[c] {
				fetched[c] = true
				keys = append(keys, cellKey(cat, c))
			}
		}
		raw, err := x.query_.Run(ctx, x.rdb, keys, nowMs-freshFor.Milliseconds(), cityTag+":drv:").StringSlice()
		if err != nil {
			return nil, err
		}
		for i := 0; i+3 < len(raw); i += 4 {
			id, _ := strconv.Atoi(raw[i])
			plat, _ := strconv.ParseFloat(raw[i+1], 64)
			plon, _ := strconv.ParseFloat(raw[i+2], 64)
			seen, _ := strconv.ParseInt(raw[i+3], 10, 64)
			if d := haversine(lat, lon, plat, plon); d <= x.p.radiusM {
				found = append(found, result{id: id, distM: d, seenMs: seen})
			}
		}
		covered, within := math.Min(x.coveredM(k), x.p.radiusM), 0
		for _, f := range found {
			if f.distM <= covered {
				within++
			}
		}
		if within >= x.p.k {
			break
		}
	}
	sort.Slice(found, func(i, j int) bool { return found[i].distM < found[j].distM })
	return found[:min(len(found), x.p.k)], nil
}

func (x *redisH3) sweep(ctx context.Context, nowMs int64) (int, error) {
	return x.swp.Run(ctx, x.rdb, []string{seenKey}, nowMs-freshFor.Milliseconds(),
		cityTag+":drv:", cityTag+":cell:").Int()
}

// ---------------------------------------------------------------- PostGIS

type postgis struct {
	pool     *pgxpool.Pool
	unlogged bool
	p        geoParams
}

func newPostgis(ctx context.Context, url string, poolSize int, unlogged bool, p geoParams) (*postgis, error) {
	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, err
	}
	cfg.MaxConns = int32(poolSize)
	if unlogged {
		cfg.AfterConnect = func(ctx context.Context, c *pgx.Conn) error {
			_, err := c.Exec(ctx, "SET synchronous_commit = off")
			return err
		}
	}
	var pool *pgxpool.Pool
	for i := 0; i < 30; i++ { // the server may still be starting
		if pool, err = pgxpool.NewWithConfig(ctx, cfg); err == nil {
			if err = pool.Ping(ctx); err == nil {
				break
			}
			pool.Close()
		}
		time.Sleep(time.Second)
	}
	if err != nil {
		return nil, err
	}
	return &postgis{pool: pool, unlogged: unlogged, p: p}, nil
}

func (x *postgis) setup(ctx context.Context, ds []*driver) error {
	table := "TABLE"
	if x.unlogged {
		table = "UNLOGGED TABLE"
	}
	for _, stmt := range []string{
		"CREATE EXTENSION IF NOT EXISTS postgis",
		"CREATE EXTENSION IF NOT EXISTS btree_gist",
		"CREATE EXTENSION IF NOT EXISTS pg_stat_statements",
		"DROP TABLE IF EXISTS driver_location",
		`CREATE ` + table + ` driver_location (
		   driver_id bigint PRIMARY KEY,
		   category  smallint NOT NULL,
		   available boolean NOT NULL,
		   seq       bigint NOT NULL,
		   seen_at   timestamptz NOT NULL,
		   geog      geography(Point, 4326) NOT NULL)`,
		"CREATE INDEX driver_location_nearby ON driver_location USING gist (category, geog) WHERE available",
	} {
		if _, err := x.pool.Exec(ctx, stmt); err != nil {
			return fmt.Errorf("%s: %w", strings.Fields(stmt)[0], err)
		}
	}
	for i := 0; i < len(ds); i += 10_000 {
		chunk := ds[i:min(i+10_000, len(ds))]
		ids, cats, avail, lons, lats := []int64{}, []int16{}, []bool{}, []float64{}, []float64{}
		for _, d := range chunk {
			ids, cats, avail = append(ids, int64(d.id)), append(cats, int16(d.cat)), append(avail, d.avail)
			lons, lats = append(lons, d.lon), append(lats, d.lat)
		}
		if _, err := x.pool.Exec(ctx, `
			INSERT INTO driver_location (driver_id, category, available, seq, seen_at, geog)
			SELECT id, cat, av, 0, now(), ST_SetSRID(ST_MakePoint(lon, lat), 4326)::geography
			FROM unnest($1::bigint[], $2::smallint[], $3::bool[], $4::float8[], $5::float8[]) AS t(id, cat, av, lon, lat)`,
			ids, cats, avail, lons, lats); err != nil {
			return err
		}
	}
	for _, stmt := range []string{"VACUUM ANALYZE driver_location", "CHECKPOINT", "SELECT pg_stat_statements_reset()"} {
		if _, err := x.pool.Exec(ctx, stmt); err != nil {
			return err
		}
	}
	return nil
}

func (x *postgis) update(ctx context.Context, d *driver, _ h3.Cell, _ bool, nowMs int64) error {
	_, err := x.pool.Exec(ctx, `UPDATE driver_location
		SET geog = ST_SetSRID(ST_MakePoint($2, $3), 4326)::geography, seq = $4,
		    seen_at = to_timestamp($5::float8 / 1000), available = $6
		WHERE driver_id = $1 AND seq < $4`, d.id, d.lon, d.lat, d.seq, nowMs, d.avail)
	return err
}

func (x *postgis) query(ctx context.Context, lat, lon float64, cat int, nowMs int64) ([]result, error) {
	rows, err := x.pool.Query(ctx, `SELECT driver_id,
		  ST_Distance(geog, ST_SetSRID(ST_MakePoint($1, $2), 4326)::geography),
		  (extract(epoch FROM seen_at) * 1000)::bigint
		FROM driver_location
		WHERE available AND category = $3 AND seen_at >= to_timestamp($4::float8 / 1000)
		  AND ST_DWithin(geog, ST_SetSRID(ST_MakePoint($1, $2), 4326)::geography, $5)
		ORDER BY geog <-> ST_SetSRID(ST_MakePoint($1, $2), 4326)::geography
		LIMIT $6`, lon, lat, int16(cat), nowMs-freshFor.Milliseconds(), x.p.radiusM, x.p.k)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []result
	for rows.Next() {
		var r result
		var id int64
		if err := rows.Scan(&id, &r.distM, &r.seenMs); err != nil {
			return nil, err
		}
		r.id = int(id)
		out = append(out, r)
	}
	return out, rows.Err()
}

func (x *postgis) sweep(context.Context, int64) (int, error) { return 0, nil } // freshness is a query filter

func (x *postgis) stats(ctx context.Context) (map[string]float64, error) {
	out := map[string]float64{}
	var wal, upd, hot, dead, autovac, size float64
	err := x.pool.QueryRow(ctx, `SELECT (SELECT wal_bytes::float8 FROM pg_stat_wal),
		  n_tup_upd::float8, n_tup_hot_upd::float8, n_dead_tup::float8, autovacuum_count::float8,
		  pg_total_relation_size('driver_location')::float8
		FROM pg_stat_user_tables WHERE relname = 'driver_location'`).Scan(&wal, &upd, &hot, &dead, &autovac, &size)
	if err != nil {
		return nil, err
	}
	out["wal_bytes"], out["n_tup_upd"], out["n_tup_hot_upd"] = wal, upd, hot
	out["n_dead_tup"], out["autovacuum_count"], out["size_mb"] = dead, autovac, size/1_048_576
	rows, err := x.pool.Query(ctx, `SELECT CASE WHEN query LIKE 'UPDATE%' THEN 'update' ELSE 'query' END,
		  sum(calls)::float8, sum(total_exec_time)::float8
		FROM pg_stat_statements
		WHERE query LIKE 'UPDATE driver_location%' OR query LIKE 'SELECT driver_id%'
		GROUP BY 1`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var kind string
		var calls, ms float64
		if err := rows.Scan(&kind, &calls, &ms); err != nil {
			return nil, err
		}
		out[kind+".calls"], out[kind+".ms"] = calls, ms
	}
	return out, rows.Err()
}

func (x *postgis) report(before, after map[string]float64, elapsed time.Duration) {
	updates := after["n_tup_upd"] - before["n_tup_upd"]
	fmt.Printf("%-14s wal=%.1fMB/s (%.0f bytes/update) hot_updates=%.0f%% dead_tuples_end=%.0f autovacuums=%.0f size_end=%.1fMB\n",
		"server", (after["wal_bytes"]-before["wal_bytes"])/elapsed.Seconds()/1_048_576,
		(after["wal_bytes"]-before["wal_bytes"])/math.Max(updates, 1),
		100*(after["n_tup_hot_upd"]-before["n_tup_hot_upd"])/math.Max(updates, 1),
		after["n_dead_tup"], after["autovacuum_count"]-before["autovacuum_count"], after["size_mb"])
	for _, kind := range []string{"update", "query"} {
		calls := after[kind+".calls"] - before[kind+".calls"]
		if calls > 0 {
			fmt.Printf("  %-12s calls/s=%-9.0f exec_ms/call=%.3f\n", kind, calls/elapsed.Seconds(),
				(after[kind+".ms"]-before[kind+".ms"])/calls)
		}
	}
}
