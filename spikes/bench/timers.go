package main

// Spike S-2: can a PostgreSQL table hold the dispatch timers (offer timeouts and
// similar) and fire them within ~1 s at cloud-tier rates? Pollers claim due rows
// with FOR UPDATE SKIP LOCKED, the pattern the Job Scheduler uses for due work.

import (
	"context"
	"flag"
	"fmt"
	"math/rand/v2"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func runTimers(args []string) error {
	fs := flag.NewFlagSet("timers", flag.ExitOnError)
	rate := fs.Float64("rate", 200, "timers created per second")
	cancelPct := fs.Float64("cancel-pct", 70, "percent of timers cancelled before they are due (offer answered)")
	delay := fs.Duration("delay", 15*time.Second, "time from creation to due")
	pollers := fs.Int("pollers", 2, "concurrent pollers, one per dispatch replica")
	pollEvery := fs.Duration("poll-every", 250*time.Millisecond, "pause between polls when nothing more is due")
	batch := fs.Int("batch", 200, "timers claimed per poll")
	producers := fs.Int("producers", 8, "concurrent timer creators")
	warmup := fs.Duration("warmup", 20*time.Second, "warm-up before measuring")
	measure := fs.Duration("measure", 60*time.Second, "measurement window")
	outage := fs.Duration("outage", 0, "if set, every poller stops for this long in the middle of the window")
	pgURL := fs.String("pg", "postgres://spike:spike@postgis:5432/spike", "PostgreSQL URL")
	if err := fs.Parse(args); err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	cfg, err := pgxpool.ParseConfig(*pgURL)
	if err != nil {
		return err
	}
	cfg.MaxConns = int32(*producers + *pollers + 8)
	var pool *pgxpool.Pool
	for i := 0; i < 30; i++ {
		if pool, err = pgxpool.NewWithConfig(ctx, cfg); err == nil {
			if err = pool.Ping(ctx); err == nil {
				break
			}
			pool.Close()
		}
		time.Sleep(time.Second)
	}
	if err != nil {
		return err
	}
	defer pool.Close()
	for _, stmt := range []string{
		"CREATE EXTENSION IF NOT EXISTS pg_stat_statements",
		"DROP TABLE IF EXISTS timers",
		`CREATE TABLE timers (
		   id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
		   kind   smallint NOT NULL,
		   ref_id bigint NOT NULL,
		   due_at timestamptz NOT NULL)`,
		"CREATE INDEX timers_due_at ON timers (due_at)",
		"SELECT pg_stat_statements_reset()",
	} {
		if _, err := pool.Exec(ctx, stmt); err != nil {
			return err
		}
	}
	fmt.Printf("rate=%.0f/s cancel=%.0f%% delay=%v pollers=%d poll_every=%v batch=%d outage=%v\n",
		*rate, *cancelPct, *delay, *pollers, *pollEvery, *batch, *outage)

	inserts, lags := &recorder{}, &recorder{}
	var measuring, paused atomic.Bool
	var created, cancelled, fired atomic.Int64
	var wg sync.WaitGroup

	for w := 0; w < *producers; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			r := rand.New(rand.NewPCG(7, uint64(w)))
			wait := pacer(*rate / float64(*producers))
			for ref := int64(w); ctx.Err() == nil; ref += int64(*producers) {
				wait()
				t0 := time.Now()
				var id int64
				err := pool.QueryRow(ctx, `INSERT INTO timers (kind, ref_id, due_at)
					VALUES (1, $1, now() + $2 * interval '1 millisecond') RETURNING id`,
					ref, delay.Milliseconds()).Scan(&id)
				if err != nil {
					if ctx.Err() == nil {
						inserts.fail()
					}
					continue
				}
				inserts.add(time.Since(t0))
				created.Add(1)
				if r.Float64()*100 < *cancelPct {
					after := time.Duration((0.2 + 0.6*r.Float64()) * float64(*delay))
					time.AfterFunc(after, func() {
						if tag, err := pool.Exec(ctx, "DELETE FROM timers WHERE id = $1", id); err == nil && tag.RowsAffected() == 1 {
							cancelled.Add(1)
						}
					})
				}
			}
		}(w)
	}

	for w := 0; w < *pollers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for ctx.Err() == nil {
				if paused.Load() {
					time.Sleep(10 * time.Millisecond)
					continue
				}
				rows, err := pool.Query(ctx, `WITH due AS (
					  SELECT id FROM timers WHERE due_at <= clock_timestamp()
					  ORDER BY due_at LIMIT $1
					  FOR UPDATE SKIP LOCKED)
					DELETE FROM timers t USING due WHERE t.id = due.id
					RETURNING extract(epoch FROM clock_timestamp() - t.due_at)::float8 * 1e6`, *batch)
				if err != nil {
					continue
				}
				n := 0
				for rows.Next() {
					var lagUs float64
					if rows.Scan(&lagUs) == nil {
						n++
						if measuring.Load() {
							lags.add(time.Duration(lagUs) * time.Microsecond)
						}
					}
				}
				rows.Close()
				fired.Add(int64(n))
				if n < *batch {
					time.Sleep(*pollEvery)
				}
			}
		}()
	}

	time.Sleep(*warmup)
	inserts.reset()
	created.Store(0)
	cancelled.Store(0)
	fired.Store(0)
	fmt.Println("MEASURE_START")
	measuring.Store(true)
	t0 := time.Now()
	if *outage > 0 {
		time.Sleep((*measure - *outage) / 2)
		paused.Store(true)
		time.Sleep(*outage)
		paused.Store(false)
		resumed := time.Now()
		peak := 0
		for time.Since(t0) < *measure {
			var due int
			if err := pool.QueryRow(ctx, "SELECT count(*) FROM timers WHERE due_at <= clock_timestamp()").Scan(&due); err == nil {
				peak = max(peak, due)
				if due <= *batch {
					fmt.Printf("outage: backlog drained %v after pollers resumed (largest backlog seen %d)\n",
						time.Since(resumed).Round(time.Millisecond), peak)
					break
				}
			}
			time.Sleep(50 * time.Millisecond)
		}
		time.Sleep(*measure - time.Since(t0))
	} else {
		time.Sleep(*measure)
	}
	elapsed := time.Since(t0)
	measuring.Store(false)
	fmt.Println("MEASURE_END")
	cancel()
	wg.Wait()

	fmt.Println(inserts.summary("insert", elapsed))
	fmt.Println(lags.summary("fire_lag", elapsed))
	var pending int
	_ = pool.QueryRow(context.Background(), "SELECT count(*) FROM timers").Scan(&pending)
	fmt.Printf("%-14s created=%d cancelled=%d fired=%d pending_at_end=%d\n", "counts",
		created.Load(), cancelled.Load(), fired.Load(), pending)
	rows, err := pool.Query(context.Background(), `SELECT left(regexp_replace(query, '\s+', ' ', 'g'), 40),
		  calls, total_exec_time / calls
		FROM pg_stat_statements WHERE query ~ 'timers' AND calls > 100 ORDER BY calls DESC LIMIT 4`)
	if err == nil {
		for rows.Next() {
			var q string
			var calls int64
			var ms float64
			if rows.Scan(&q, &calls, &ms) == nil {
				fmt.Printf("  %-42s calls=%-8d exec_ms/call=%.3f\n", q, calls, ms)
			}
		}
		rows.Close()
	}
	return nil
}
