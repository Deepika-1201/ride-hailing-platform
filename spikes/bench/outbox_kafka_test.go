package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"
)

const kafkaTestEnvelope = `{"event_id":"event-1","event_type":"OfferCreated","event_version":2,
	"aggregate_type":"offer","aggregate_id":"offer-1","aggregate_version":0,
	"trace_parent":"00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
	"payload":{"integer":9007199254740993,"text":"unchanged"}}`

func TestOutboxKafkaRecordPreservesEnvelopeKeyAndHeaders(test *testing.T) {
	row := outboxRelayRow{id: 1, partitionKey: "ride-1", envelope: []byte(kafkaTestEnvelope)}
	record, err := outboxKafkaRecord("spike-s4-test", row)
	if err != nil {
		test.Fatal(err)
	}
	if record.Topic != "spike-s4-test" || string(record.Key) != "ride-1" || !bytes.Equal(record.Value, row.envelope) {
		test.Fatalf("changed envelope or routing: %+v", record)
	}
	wanted := []kgo.RecordHeader{
		{Key: "event_id", Value: []byte("event-1")},
		{Key: "event_type", Value: []byte("OfferCreated")},
		{Key: "event_version", Value: []byte("2")},
		{Key: "traceparent", Value: []byte("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")},
	}
	if !reflect.DeepEqual(record.Headers, wanted) {
		test.Fatalf("headers=%+v", record.Headers)
	}
	row.envelope[0] = '!'
	if string(record.Value) != kafkaTestEnvelope {
		test.Fatal("record aliases caller-owned envelope bytes")
	}
}

func TestOutboxKafkaRecordRejectsInvalidInputs(test *testing.T) {
	row := outboxRelayRow{id: 1, partitionKey: "ride-1", envelope: []byte(kafkaTestEnvelope)}
	for _, topic := range []string{"", "rides.events", "spike-s4-", "spike-s4- space", "spike-s4-" + strings.Repeat("a", 241)} {
		if _, err := outboxKafkaRecord(topic, row); err == nil {
			test.Errorf("accepted invalid topic %q", topic)
		}
	}
	for _, envelope := range []string{"", "{", "null", "{}", kafkaTestEnvelope + "{}"} {
		invalid := row
		invalid.envelope = []byte(envelope)
		if _, err := outboxKafkaRecord("spike-s4-test", invalid); err == nil {
			test.Errorf("accepted invalid envelope %q", envelope)
		}
	}
	for _, field := range []string{"event_id", "event_type", "event_version", "aggregate_type", "aggregate_id", "aggregate_version"} {
		var envelope map[string]json.RawMessage
		if err := json.Unmarshal(row.envelope, &envelope); err != nil {
			test.Fatal(err)
		}
		delete(envelope, field)
		invalid := row
		var err error
		invalid.envelope, err = json.Marshal(envelope)
		if err != nil {
			test.Fatal(err)
		}
		if _, err := outboxKafkaRecord("spike-s4-test", invalid); err == nil {
			test.Errorf("accepted missing %s", field)
		}
	}
	for _, invalid := range []outboxRelayRow{{id: 0, partitionKey: row.partitionKey, envelope: row.envelope}, {id: 1, envelope: row.envelope}} {
		if _, err := outboxKafkaRecord("spike-s4-test", invalid); err == nil {
			test.Errorf("accepted invalid row %+v", invalid)
		}
	}
}

func TestOutboxKafkaPublisherUsesIdempotencyAndAllAcknowledgements(test *testing.T) {
	publisher, err := newKafkaOutboxPublisher([]string{"localhost:1"}, "spike-s4-test", time.Second)
	if err != nil {
		test.Fatal(err)
	}
	defer publisher.close()
	if publisher.client.OptValue(kgo.DisableIdempotentWrite) != false ||
		!reflect.DeepEqual(publisher.client.OptValue(kgo.RequiredAcks), kgo.AllISRAcks()) ||
		publisher.client.OptValue(kgo.ProducerLinger) != 5*time.Millisecond ||
		!reflect.DeepEqual(publisher.client.OptValue(kgo.ProducerBatchCompression), []kgo.CompressionCodec{kgo.ZstdCompression()}) {
		test.Fatal("publisher settings differ from the S-4 baseline")
	}
}

func TestOutboxKafkaPublishDeadlineClosesProducer(test *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		test.Fatal(err)
	}
	defer listener.Close()
	publisher, err := newKafkaOutboxPublisher([]string{listener.Addr().String()}, "spike-s4-timeout", 100*time.Millisecond)
	if err != nil {
		test.Fatal(err)
	}
	defer publisher.close()
	row := outboxRelayRow{id: 1, partitionKey: "ride-1", envelope: []byte(kafkaTestEnvelope)}
	started := time.Now()
	if err := publisher.publish(test.Context(), row); !errors.Is(err, context.DeadlineExceeded) {
		test.Fatalf("deadline returned %v", err)
	}
	if time.Since(started) > 2*time.Second {
		test.Fatal("broker silence outlived the bounded publish deadline")
	}
	if err := publisher.publish(test.Context(), row); !errors.Is(err, kgo.ErrClientClosed) {
		test.Fatalf("timed-out producer was reused: %v", err)
	}
}

func TestOutboxKafkaCaptureStopsAtSnapshotOffsets(test *testing.T) {
	capture, err := newOutboxKafkaCapture("spike-s4-test", map[int32]int64{0: 2, 1: 0})
	if err != nil {
		test.Fatal(err)
	}
	var output bytes.Buffer
	for offset := range int64(3) {
		record, err := outboxKafkaRecord(capture.Topic, outboxRelayRow{id: 1, partitionKey: "ride-1", envelope: []byte(kafkaTestEnvelope)})
		if err != nil {
			test.Fatal(err)
		}
		record.Offset = offset
		if err := capture.appendRecord(&output, record); err != nil {
			test.Fatal(err)
		}
	}
	if capture.Records != 2 || !capture.complete() || bytes.Count(output.Bytes(), []byte{'\n'}) != 2 {
		test.Fatalf("capture=%+v output=%s", capture, output.String())
	}
	report, err := verifyOutboxTrace(strings.NewReader(kafkaTestEnvelope), &output)
	if err != nil || !report.ok() || report.Unique != 1 || report.Duplicates != 1 {
		test.Fatalf("report=%+v error=%v", report, err)
	}
}

type outboxFailingWriter struct{}

func (outboxFailingWriter) Write([]byte) (int, error) { return 0, io.ErrClosedPipe }

func TestOutboxKafkaCaptureRejectsMissingOffsetsAndCorruptRecords(test *testing.T) {
	for _, testCase := range []struct {
		name   string
		change func(*kgo.Record)
	}{
		{name: "offset gap", change: func(record *kgo.Record) { record.Offset = 1 }},
		{name: "wrong topic", change: func(record *kgo.Record) { record.Topic = "spike-s4-other" }},
		{name: "unknown partition", change: func(record *kgo.Record) { record.Partition = 9 }},
		{name: "missing key", change: func(record *kgo.Record) { record.Key = nil }},
		{name: "missing headers", change: func(record *kgo.Record) { record.Headers = nil }},
		{name: "wrong header", change: func(record *kgo.Record) { record.Headers[0].Value = []byte("wrong") }},
		{name: "duplicate header", change: func(record *kgo.Record) { record.Headers = append(record.Headers, record.Headers[0]) }},
		{name: "invalid JSON", change: func(record *kgo.Record) { record.Value = []byte("{") }},
		{name: "transaction", change: func(record *kgo.Record) { record.Attrs = kgo.NewRecordAttrs(kgo.RecordAttrsOpts{Transactional: true}) }},
	} {
		test.Run(testCase.name, func(test *testing.T) {
			capture, err := newOutboxKafkaCapture("spike-s4-test", map[int32]int64{0: 2})
			if err != nil {
				test.Fatal(err)
			}
			record, err := outboxKafkaRecord(capture.Topic, outboxRelayRow{id: 1, partitionKey: "ride-1", envelope: []byte(kafkaTestEnvelope)})
			if err != nil {
				test.Fatal(err)
			}
			testCase.change(record)
			if err := capture.appendRecord(io.Discard, record); err == nil || capture.Records != 0 {
				test.Fatalf("corrupt record accepted: capture=%+v error=%v", capture, err)
			}
		})
	}
	capture, err := newOutboxKafkaCapture("spike-s4-test", map[int32]int64{0: 2})
	if err != nil {
		test.Fatal(err)
	}
	record, err := outboxKafkaRecord(capture.Topic, outboxRelayRow{id: 1, partitionKey: "ride-1", envelope: []byte(kafkaTestEnvelope)})
	if err != nil {
		test.Fatal(err)
	}
	if err := capture.appendRecord(outboxFailingWriter{}, record); !errors.Is(err, io.ErrClosedPipe) || capture.Records != 0 {
		test.Fatalf("write failure advanced capture: capture=%+v error=%v", capture, err)
	}
	if err := capture.appendRecord(io.Discard, record); err != nil {
		test.Fatal(err)
	}
	if err := capture.appendRecord(io.Discard, record); err == nil || capture.Records != 1 {
		test.Fatalf("duplicate offset accepted: capture=%+v error=%v", capture, err)
	}
}

func kafkaOutboxTestTopic(test *testing.T) ([]string, string, context.Context) {
	test.Helper()
	address := os.Getenv("SPIKE_TEST_KAFKA")
	if address == "" {
		if os.Getenv("CI") == "true" {
			test.Fatal("SPIKE_TEST_KAFKA is required in CI")
		}
		test.Skip("set SPIKE_TEST_KAFKA to run the isolated Kafka tests")
	}
	brokers := strings.Split(address, ",")
	ctx, cancel := context.WithTimeout(test.Context(), 30*time.Second)
	test.Cleanup(cancel)
	client, err := kgo.NewClient(kgo.SeedBrokers(brokers...))
	if err != nil {
		test.Fatal(err)
	}
	test.Cleanup(client.Close)
	admin := kadm.NewClient(client)
	topic := "spike-s4-" + strings.ToLower(rand.Text())
	if _, err := admin.CreateTopic(ctx, 3, 1, map[string]*string{
		"cleanup.policy": kadm.StringPtr("delete"),
		"retention.ms":   kadm.StringPtr("3600000"),
	}, topic); err != nil {
		test.Fatal(err)
	}
	test.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := admin.DeleteTopic(cleanup, topic); err != nil {
			test.Error(err)
		}
	})
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		details, err := admin.ListTopics(ctx, topic)
		if err != nil {
			test.Fatal(err)
		}
		detail, exists := details[topic]
		ready := exists && detail.Err == nil && len(detail.Partitions) == 3
		for _, partition := range detail.Partitions {
			ready = ready && partition.Err == nil && partition.Leader >= 0 && len(partition.ISR) > 0
		}
		if ready {
			return brokers, topic, ctx
		}
		select {
		case <-ctx.Done():
			test.Fatal(ctx.Err())
		case <-ticker.C:
		}
	}
}

func kafkaOutboxTestRow(test *testing.T, eventID, key string, version int64) outboxRelayRow {
	test.Helper()
	envelope, err := json.Marshal(map[string]any{
		"event_id": eventID, "event_type": "SpikeChanged", "event_version": 1,
		"aggregate_type": "ride", "aggregate_id": key, "aggregate_version": version,
		"occurred_at": "2026-10-11T00:00:00Z", "producer": "spike:worker",
		"correlation_id": key, "causation_id": nil,
		"trace_parent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
		"payload":      map[string]any{"integer": int64(9007199254740993)},
	})
	if err != nil {
		test.Fatal(err)
	}
	return outboxRelayRow{id: version + 1, partitionKey: key, envelope: envelope}
}

func TestKafkaOutboxRoundTripAndEmptyCapture(test *testing.T) {
	brokers, topic, ctx := kafkaOutboxTestTopic(test)
	var received bytes.Buffer
	capture, err := captureOutboxKafkaTrace(ctx, brokers, topic, &received)
	if err != nil || capture.Records != 0 || len(capture.EndOffsets) != 3 || received.Len() != 0 {
		test.Fatalf("empty capture=%+v error=%v", capture, err)
	}
	publisher, err := newKafkaOutboxPublisher(brokers, topic, 5*time.Second)
	if err != nil {
		test.Fatal(err)
	}
	defer publisher.close()
	var expected bytes.Buffer
	envelopes := map[string][]byte{}
	for version := range int64(2) {
		for keyNumber := range 9 {
			eventID := fmt.Sprintf("event-%d-%d", keyNumber, version)
			row := kafkaOutboxTestRow(test, eventID, fmt.Sprintf("ride-%d", keyNumber), version)
			if err := publisher.publish(ctx, row); err != nil {
				test.Fatal(err)
			}
			expected.Write(row.envelope)
			expected.WriteByte('\n')
			envelopes[eventID] = row.envelope
		}
	}
	capture, err = captureOutboxKafkaTrace(ctx, brokers, topic, &received)
	if err != nil || capture.Records != 18 {
		test.Fatalf("capture=%+v error=%v", capture, err)
	}
	populated := 0
	for _, end := range capture.EndOffsets {
		if end > 0 {
			populated++
		}
	}
	if populated < 2 {
		test.Fatal("round trip did not exercise multiple partitions")
	}
	report, err := verifyOutboxTrace(&expected, bytes.NewReader(received.Bytes()))
	if err != nil || !report.ok() || report.Unique != 18 || report.Duplicates != 0 {
		test.Fatalf("report=%+v error=%v", report, err)
	}
	decoder := json.NewDecoder(&received)
	for range capture.Records {
		var value json.RawMessage
		if err := decoder.Decode(&value); err != nil {
			test.Fatal(err)
		}
		var identity outboxTraceEvent
		if err := json.Unmarshal(value, &identity); err != nil {
			test.Fatal(err)
		}
		if !bytes.Equal(value, envelopes[identity.EventID]) {
			test.Fatalf("Kafka changed envelope %s: %s", identity.EventID, value)
		}
	}
}

func TestKafkaOutboxReplaysAcknowledgedBatchAfterTakeover(test *testing.T) {
	brokers, topic, ctx := kafkaOutboxTestTopic(test)
	store, _ := postgresOutboxTestStore(test)
	rows := []outboxRelayRow{
		kafkaOutboxTestRow(test, "first", "ride-1", 0),
		kafkaOutboxTestRow(test, "second", "ride-1", 1),
	}
	var expected bytes.Buffer
	for index := range rows {
		row := &rows[index]
		if err := store.pool.QueryRow(ctx, fmt.Sprintf("INSERT INTO %s (partition_key, envelope) VALUES ($1, $2) RETURNING id", store.outbox),
			row.partitionKey, row.envelope).Scan(&row.id); err != nil {
			test.Fatal(err)
		}
		expected.Write(row.envelope)
		expected.WriteByte('\n')
	}
	first, acquired, err := store.acquireLease(ctx, "first")
	if err != nil || !acquired {
		test.Fatalf("acquired=%v error=%v", acquired, err)
	}
	firstPublisher, err := newKafkaOutboxPublisher(brokers, topic, 5*time.Second)
	if err != nil {
		test.Fatal(err)
	}
	defer firstPublisher.close()
	failure := errors.New("stopped after Kafka acknowledgement, before database marking")
	relay := outboxRelay{store: store, lease: first, batchSize: 2,
		publish: func(ctx context.Context, row outboxRelayRow) error {
			if err := firstPublisher.publish(ctx, row); err != nil {
				return err
			}
			if row.id == rows[1].id {
				return failure
			}
			return nil
		},
	}
	if count, err := relay.relayBatch(ctx); count != 0 || !errors.Is(err, failure) {
		test.Fatalf("failed batch count=%d error=%v", count, err)
	}
	firstPublisher.close()
	if pending, err := store.pending(ctx, 2); err != nil || len(pending) != 2 {
		test.Fatalf("unmarked events lost: pending=%+v error=%v", pending, err)
	}
	if _, err := store.pool.Exec(ctx, fmt.Sprintf("UPDATE %s SET expires_at = clock_timestamp() - interval '1 second'", store.leases)); err != nil {
		test.Fatal(err)
	}
	second, acquired, err := store.acquireLease(ctx, "second")
	if err != nil || !acquired || second.token != first.token+1 {
		test.Fatalf("successor=%+v acquired=%v error=%v", second, acquired, err)
	}
	if marked, err := store.markPublished(ctx, first, []int64{rows[0].id, rows[1].id}); err != nil || marked {
		test.Fatalf("old holder marked: marked=%v error=%v", marked, err)
	}
	secondPublisher, err := newKafkaOutboxPublisher(brokers, topic, 5*time.Second)
	if err != nil {
		test.Fatal(err)
	}
	defer secondPublisher.close()
	relay.lease = second
	relay.publish = secondPublisher.publish
	if count, err := relay.relayBatch(ctx); err != nil || count != 2 {
		test.Fatalf("successor batch count=%d error=%v", count, err)
	}
	if pending, err := store.pending(ctx, 2); err != nil || len(pending) != 0 {
		test.Fatalf("successor did not mark: pending=%+v error=%v", pending, err)
	}
	var received bytes.Buffer
	capture, err := captureOutboxKafkaTrace(ctx, brokers, topic, &received)
	if err != nil || capture.Records != 4 {
		test.Fatalf("capture=%+v error=%v", capture, err)
	}
	report, err := verifyOutboxTrace(&expected, &received)
	if err != nil || !report.ok() || report.Unique != 2 || report.Duplicates != 2 {
		test.Fatalf("replay report=%+v error=%v", report, err)
	}
}

func TestOutboxCaptureCommandValidationAndOverwriteProtection(test *testing.T) {
	if err := runOutboxCapture([]string{"-h"}); err != nil {
		test.Fatal(err)
	}
	path := filepath.Join(test.TempDir(), "received.jsonl")
	for _, args := range [][]string{
		nil,
		{"-topic", "rides.events", "-output", path},
		{"-topic", "spike-s4-test", "-output", path, "-timeout", "0s"},
		{"-topic", "spike-s4-test", "-output", path, "-brokers", ""},
		{"-topic", "spike-s4-test", "-output", path, "-brokers", "localhost:9092,"},
		{"-topic", "spike-s4-test", "-output", path, "extra"},
	} {
		if err := runOutboxCapture(args); err == nil {
			test.Errorf("accepted invalid arguments: %v", args)
		}
		if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
			test.Fatalf("invalid arguments created output: %v", err)
		}
	}
	if err := os.WriteFile(path, []byte("keep this evidence"), 0600); err != nil {
		test.Fatal(err)
	}
	if err := runOutboxCapture([]string{"-topic", "spike-s4-test", "-output", path}); !errors.Is(err, os.ErrExist) {
		test.Fatalf("existing output accepted: %v", err)
	}
	if content, err := os.ReadFile(path); err != nil || string(content) != "keep this evidence" {
		test.Fatalf("existing evidence changed: %q error=%v", content, err)
	}
}

func TestKafkaOutboxCaptureCommandAndTruncatedPrefix(test *testing.T) {
	brokers, topic, ctx := kafkaOutboxTestTopic(test)
	publisher, err := newKafkaOutboxPublisher(brokers, topic, 5*time.Second)
	if err != nil {
		test.Fatal(err)
	}
	defer publisher.close()
	row := kafkaOutboxTestRow(test, "event-1", "ride-1", 0)
	if err := publisher.publish(ctx, row); err != nil {
		test.Fatal(err)
	}
	path := filepath.Join(test.TempDir(), "received.jsonl")
	if err := runOutboxCapture([]string{"-brokers", strings.Join(brokers, ","), "-topic", topic, "-output", path}); err != nil {
		test.Fatal(err)
	}
	content, err := os.ReadFile(path)
	if err != nil {
		test.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0600 || !bytes.Equal(bytes.TrimSpace(content), row.envelope) {
		test.Fatalf("capture file differs or is not private: info=%v error=%v", info, err)
	}
	admin := kadm.NewClient(publisher.client)
	ends, err := admin.ListEndOffsets(ctx, topic)
	if err != nil {
		test.Fatal(err)
	}
	if err := ends.Error(); err != nil {
		test.Fatal(err)
	}
	deleted, err := admin.DeleteRecords(ctx, ends.Offsets())
	if err != nil {
		test.Fatal(err)
	}
	if err := deleted.Error(); err != nil {
		test.Fatal(err)
	}
	if capture, err := captureOutboxKafkaTrace(ctx, brokers, topic, io.Discard); err == nil || capture.Records != 0 {
		test.Fatalf("truncated prefix accepted: capture=%+v error=%v", capture, err)
	}
}
