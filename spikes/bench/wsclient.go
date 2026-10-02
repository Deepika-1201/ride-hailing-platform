package main

// Load client for spike S-3: holds N WebSocket connections that each send a
// location update every 4 s, and reads the server's memory figures at every step.

import (
	"context"
	"flag"
	"fmt"
	"io"
	"math/rand/v2"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

func runWSClient(args []string) error {
	fs := flag.NewFlagSet("wsclient", flag.ExitOnError)
	url := fs.String("url", "ws://ws-servlet:8080/ws", "WebSocket URL")
	statsURL := fs.String("stats", "http://ws-servlet:8080/stats", "server stats URL")
	stepsFlag := fs.String("steps", "5000,10000,20000", "connection counts to hold, in order")
	ramp := fs.Int("ramp", 1000, "new connections per second")
	hold := fs.Duration("hold", 20*time.Second, "how long to hold each step before reading stats")
	every := fs.Duration("every", 4*time.Second, "location update interval per connection")
	if err := fs.Parse(args); err != nil {
		return err
	}
	var steps []int
	for _, s := range strings.Split(*stepsFlag, ",") {
		n, err := strconv.Atoi(strings.TrimSpace(s))
		if err != nil {
			return err
		}
		steps = append(steps, n)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var stats string
	for i := 0; i < 60; i++ { // wait for the server to start
		if stats, _ = getStats(*statsURL); stats != "" {
			break
		}
		time.Sleep(time.Second)
	}
	fmt.Println("STATS connections=0", stats)

	dialer := &websocket.Dialer{ReadBufferSize: 256, WriteBufferSize: 256, HandshakeTimeout: 10 * time.Second}
	var open, failed, acks atomic.Int64
	var wg sync.WaitGroup
	total := 0
	for _, target := range steps {
		tick := time.NewTicker(time.Second / time.Duration(*ramp))
		sem := make(chan struct{}, 32)
		for ; total < target; total++ {
			if failed.Load() >= 1000 {
				fmt.Printf("ABORT connections=%d failed=%d: the server stopped accepting connections\n", open.Load(), failed.Load())
				break
			}
			<-tick.C
			sem <- struct{}{}
			wg.Add(1)
			go func() {
				defer wg.Done()
				c, _, err := dialer.Dial(*url, nil)
				<-sem
				if err != nil {
					failed.Add(1)
					return
				}
				open.Add(1)
				defer open.Add(-1)
				drive(ctx, c, *every, &failed, &acks)
			}()
		}
		tick.Stop()
		for len(sem) > 0 {
			time.Sleep(10 * time.Millisecond)
		}
		fmt.Printf("HOLD_START connections=%d\n", open.Load())
		acksBefore := acks.Load()
		time.Sleep(*hold)
		fmt.Printf("STEP connections=%d failed=%d acks_per_s=%.0f\n",
			open.Load(), failed.Load(), float64(acks.Load()-acksBefore)/hold.Seconds())
		stats, err := getStats(*statsURL)
		if err != nil {
			fmt.Printf("ABORT stats unavailable: %v\n", err)
			break
		}
		fmt.Printf("STATS connections=%d %s\n", open.Load(), stats)
		if failed.Load() >= 1000 {
			break
		}
	}
	cancel()
	wg.Wait()
	return nil
}

var statsClient = &http.Client{Timeout: 30 * time.Second}

// drive sends a location update every interval (first one at a random offset) and reads acknowledgements.
func drive(ctx context.Context, c *websocket.Conn, every time.Duration, failed, acks *atomic.Int64) {
	defer c.Close()
	go func() {
		for {
			if _, _, err := c.ReadMessage(); err != nil {
				return
			}
			acks.Add(1)
		}
	}()
	t := time.NewTimer(rand.N(every))
	defer t.Stop()
	for seq := 1; ; seq++ {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			msg := fmt.Sprintf(`{"type":"location","seq":%d,"lat":12.97571,"lon":77.60502,"accuracy":8.5,"heading":91.0,"speed":7.2,"deviceTime":%d}`,
				seq, time.Now().UnixMilli())
			if err := c.WriteMessage(websocket.TextMessage, []byte(msg)); err != nil {
				failed.Add(1)
				return
			}
			t.Reset(every)
		}
	}
}

func getStats(url string) (string, error) {
	resp, err := statsClient.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	return string(body), err
}
