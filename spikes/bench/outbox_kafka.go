package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"maps"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"
)

var outboxKafkaTopic = regexp.MustCompile(`^spike-s4-[a-zA-Z0-9._-]+$`)

type kafkaOutboxPublisher struct {
	client  *kgo.Client
	topic   string
	timeout time.Duration
}

func validateOutboxKafkaTopic(topic string) error {
	if len(topic) > 249 || !outboxKafkaTopic.MatchString(topic) {
		return errors.New("a valid spike-s4-* topic of at most 249 bytes is required")
	}
	return nil
}

func newKafkaOutboxPublisher(brokers []string, topic string, timeout time.Duration) (*kafkaOutboxPublisher, error) {
	if err := validateOutboxKafkaTopic(topic); err != nil {
		return nil, err
	}
	if len(brokers) == 0 || timeout <= 0 {
		return nil, errors.New("brokers and a positive publish timeout are required")
	}
	for _, broker := range brokers {
		if strings.TrimSpace(broker) == "" {
			return nil, errors.New("broker addresses must not be empty")
		}
	}
	client, err := kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ClientID("spike-s4-relay"),
		kgo.RequiredAcks(kgo.AllISRAcks()),
		kgo.ProducerBatchCompression(kgo.ZstdCompression()),
		kgo.ProducerLinger(5*time.Millisecond),
		kgo.RecordDeliveryTimeout(max(timeout, time.Second)),
	)
	if err != nil {
		return nil, err
	}
	return &kafkaOutboxPublisher{client: client, topic: topic, timeout: timeout}, nil
}

func outboxKafkaRecord(topic string, row outboxRelayRow) (*kgo.Record, error) {
	if err := validateOutboxKafkaTopic(topic); err != nil {
		return nil, err
	}
	if row.id < 1 || row.partitionKey == "" {
		return nil, errors.New("a positive row ID and partition key are required")
	}
	var metadata struct {
		outboxTraceEvent
		EventType    string `json:"event_type"`
		EventVersion *int64 `json:"event_version"`
		TraceParent  string `json:"trace_parent"`
	}
	if err := json.Unmarshal(row.envelope, &metadata); err != nil {
		return nil, fmt.Errorf("decode envelope: %w", err)
	}
	if metadata.EventID == "" || metadata.EventType == "" || metadata.EventVersion == nil || *metadata.EventVersion < 1 ||
		metadata.AggregateType == "" || metadata.AggregateID == "" || metadata.AggregateVersion == nil || *metadata.AggregateVersion < 0 {
		return nil, errors.New("event identity, type, positive event version and nonnegative aggregate version are required")
	}
	headers := []kgo.RecordHeader{
		{Key: "event_id", Value: []byte(metadata.EventID)},
		{Key: "event_type", Value: []byte(metadata.EventType)},
		{Key: "event_version", Value: []byte(strconv.FormatInt(*metadata.EventVersion, 10))},
	}
	if metadata.TraceParent != "" {
		headers = append(headers, kgo.RecordHeader{Key: "traceparent", Value: []byte(metadata.TraceParent)})
	}
	return &kgo.Record{Topic: topic, Key: []byte(row.partitionKey), Value: bytes.Clone(row.envelope), Headers: headers}, nil
}

func (publisher *kafkaOutboxPublisher) publish(ctx context.Context, row outboxRelayRow) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	record, err := outboxKafkaRecord(publisher.topic, row)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, publisher.timeout)
	defer cancel()
	stop := context.AfterFunc(ctx, publisher.client.Close)
	defer stop()
	err = publisher.client.ProduceSync(ctx, record).FirstErr()
	if ctx.Err() != nil {
		return ctx.Err()
	}
	return err
}

func (publisher *kafkaOutboxPublisher) close() {
	publisher.client.Close()
}

type outboxKafkaCapture struct {
	Topic      string          `json:"topic"`
	EndOffsets map[int32]int64 `json:"end_offsets"`
	Records    int64           `json:"records"`
	next       map[int32]int64
}

func newOutboxKafkaCapture(topic string, ends map[int32]int64) (outboxKafkaCapture, error) {
	capture := outboxKafkaCapture{Topic: topic, EndOffsets: maps.Clone(ends), next: map[int32]int64{}}
	if err := validateOutboxKafkaTopic(topic); err != nil {
		return capture, err
	}
	if len(ends) == 0 {
		return capture, errors.New("the capture requires at least one partition")
	}
	for partition, end := range ends {
		if partition < 0 || end < 0 {
			return capture, errors.New("partition IDs and end offsets must be nonnegative")
		}
	}
	return capture, nil
}

func (capture outboxKafkaCapture) complete() bool {
	for partition, end := range capture.EndOffsets {
		if capture.next[partition] != end {
			return false
		}
	}
	return true
}

func (capture *outboxKafkaCapture) appendRecord(output io.Writer, record *kgo.Record) error {
	end, exists := capture.EndOffsets[record.Partition]
	if record.Topic != capture.Topic || !exists {
		return errors.New("received a record outside the captured topic partitions")
	}
	next := capture.next[record.Partition]
	if next == end && record.Offset >= end {
		return nil
	}
	if record.Offset != next {
		return fmt.Errorf("partition %d: expected offset %d, received %d", record.Partition, next, record.Offset)
	}
	if record.Attrs.IsControl() || record.Attrs.IsTransactional() {
		return errors.New("capture requires a nontransactional append-only spike topic")
	}
	wanted, err := outboxKafkaRecord(capture.Topic, outboxRelayRow{id: 1, partitionKey: string(record.Key), envelope: record.Value})
	if err != nil {
		return err
	}
	for _, header := range wanted.Headers {
		matches := 0
		for _, received := range record.Headers {
			if received.Key == header.Key {
				if !bytes.Equal(received.Value, header.Value) {
					return fmt.Errorf("header %s does not match its envelope", header.Key)
				}
				matches++
			}
		}
		if matches != 1 {
			return fmt.Errorf("header %s must occur exactly once", header.Key)
		}
	}
	if err := json.NewEncoder(output).Encode(json.RawMessage(record.Value)); err != nil {
		return err
	}
	capture.next[record.Partition] = next + 1
	capture.Records++
	return nil
}

func captureOutboxKafkaTrace(ctx context.Context, brokers []string, topic string, output io.Writer) (outboxKafkaCapture, error) {
	var capture outboxKafkaCapture
	if err := validateOutboxKafkaTopic(topic); err != nil {
		return capture, err
	}
	if len(brokers) == 0 || output == nil {
		return capture, errors.New("brokers and trace output are required")
	}
	for _, broker := range brokers {
		if strings.TrimSpace(broker) == "" {
			return capture, errors.New("broker addresses must not be empty")
		}
	}
	client, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.ClientID("spike-s4-capture"),
		kgo.ConsumeResetOffset(kgo.NoResetOffset()), kgo.FetchMaxWait(250*time.Millisecond),
		kgo.FetchMaxBytes(1<<20), kgo.MaxConcurrentFetches(1))
	if err != nil {
		return capture, err
	}
	defer client.Close()
	admin := kadm.NewClient(client)
	starts, err := admin.ListStartOffsets(ctx, topic)
	if err != nil {
		return capture, err
	}
	if err := starts.Error(); err != nil {
		return capture, err
	}
	ends, err := admin.ListEndOffsets(ctx, topic)
	if err != nil {
		return capture, err
	}
	if err := ends.Error(); err != nil {
		return capture, err
	}
	boundaries := map[int32]int64{}
	assigned := map[int32]kgo.Offset{}
	for partition, end := range ends[topic] {
		start, exists := starts.Lookup(topic, partition)
		if !exists || start.Offset != 0 {
			return capture, fmt.Errorf("partition %d is missing or has already lost its offset-zero prefix", partition)
		}
		boundaries[partition] = end.Offset
		if end.Offset > 0 {
			assigned[partition] = kgo.NoResetOffset().At(0)
		}
	}
	capture, err = newOutboxKafkaCapture(topic, boundaries)
	if err != nil {
		return capture, err
	}
	if len(starts[topic]) != len(boundaries) {
		return capture, errors.New("topic partitions changed while capturing offsets")
	}
	client.AddConsumePartitions(map[string]map[int32]kgo.Offset{topic: assigned})
	for !capture.complete() {
		fetches := client.PollFetches(ctx)
		if err := fetches.Err(); err != nil {
			return capture, err
		}
		for _, fetch := range fetches {
			for _, fetchedTopic := range fetch.Topics {
				for _, partition := range fetchedTopic.Partitions {
					if partition.LogStartOffset != 0 {
						return capture, fmt.Errorf("partition %d was truncated during capture", partition.Partition)
					}
					for _, record := range partition.Records {
						if err := capture.appendRecord(output, record); err != nil {
							return capture, err
						}
					}
					if capture.next[partition.Partition] == capture.EndOffsets[partition.Partition] {
						client.RemoveConsumePartitions(map[string][]int32{topic: {partition.Partition}})
					}
				}
			}
		}
	}
	return capture, nil
}

func runOutboxCapture(args []string) error {
	flags := flag.NewFlagSet("outbox-capture", flag.ContinueOnError)
	addresses := flags.String("brokers", "localhost:29092", "comma-separated Kafka bootstrap addresses")
	topic := flags.String("topic", "", "spike-s4-* topic to capture after publishing has stopped")
	path := flags.String("output", "", "new JSON Lines trace file; existing files are never overwritten")
	timeout := flags.Duration("timeout", 30*time.Second, "maximum capture duration")
	if err := flags.Parse(args); errors.Is(err, flag.ErrHelp) {
		return nil
	} else if err != nil {
		return err
	}
	if *path == "" || strings.TrimSpace(*addresses) == "" || *timeout <= 0 || flags.NArg() != 0 {
		return errors.New("-topic, -output, brokers and a positive timeout are required")
	}
	if err := validateOutboxKafkaTopic(*topic); err != nil {
		return err
	}
	brokers := strings.Split(*addresses, ",")
	for index := range brokers {
		brokers[index] = strings.TrimSpace(brokers[index])
		if brokers[index] == "" {
			return errors.New("broker addresses must not be empty")
		}
	}
	output, err := os.OpenFile(*path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()
	capture, captureErr := captureOutboxKafkaTrace(ctx, brokers, *topic, output)
	closeErr := output.Close()
	if err := errors.Join(captureErr, closeErr); err != nil {
		return fmt.Errorf("capture incomplete; partial trace at %s: %w", *path, err)
	}
	return json.NewEncoder(os.Stdout).Encode(capture)
}
