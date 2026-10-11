//go:build replicated

package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"
)

func waitForOutboxReplicaPartition(ctx context.Context, admin *kadm.Client, topic string, expectedISR int, unavailable int32) (kadm.PartitionDetail, error) {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	var last kadm.PartitionDetail
	var lastErr error
	for {
		details, err := admin.ListTopics(ctx, topic)
		lastErr = err
		if err == nil {
			detail, exists := details[topic]
			last = detail.Partitions[0]
			if exists && detail.Err == nil && len(detail.Partitions) == 1 && outboxReplicaPartitionReady(last, expectedISR, unavailable) {
				return last, nil
			}
		}
		select {
		case <-ctx.Done():
			return last, fmt.Errorf("replica readiness (leader=%d ISR=%v unavailable=%d): %w", last.Leader, last.ISR, unavailable, errors.Join(ctx.Err(), lastErr))
		case <-ticker.C:
		}
	}
}

func TestKafkaOutboxSurvivesReplicatedLeaderLoss(test *testing.T) {
	addresses := os.Getenv("SPIKE_TEST_KAFKA_REPLICATED_BROKERS")
	identifiers := strings.Fields(os.Getenv("SPIKE_TEST_KAFKA_REPLICATED_CONTAINERS"))
	if addresses == "" || len(identifiers) != 3 {
		test.Fatal("replicated tests require SPIKE_TEST_KAFKA_REPLICATED_BROKERS and exactly three SPIKE_TEST_KAFKA_REPLICATED_CONTAINERS")
	}
	ctx, cancel := context.WithTimeout(test.Context(), 2*time.Minute)
	defer cancel()
	var containers []outboxKafkaContainerInfo
	for _, identifier := range identifiers {
		container, err := inspectOutboxKafkaContainer(ctx, identifier)
		if err != nil {
			test.Fatal(err)
		}
		containers = append(containers, container)
	}
	targets, err := outboxReplicaTargets(containers)
	if err != nil {
		test.Fatal(err)
	}
	brokers := strings.Split(addresses, ",")
	adminClient, err := kgo.NewClient(kgo.SeedBrokers(brokers...))
	if err != nil {
		test.Fatal(err)
	}
	test.Cleanup(adminClient.Close)
	admin := kadm.NewClient(adminClient)
	topic := "spike-s4-replicated-" + strings.ToLower(rand.Text())
	created, err := admin.CreateTopic(ctx, 1, 3, map[string]*string{
		"cleanup.policy":                 kadm.StringPtr("delete"),
		"retention.ms":                   kadm.StringPtr("3600000"),
		"min.insync.replicas":            kadm.StringPtr("2"),
		"unclean.leader.election.enable": kadm.StringPtr("false"),
	}, topic)
	if err != nil {
		test.Fatal(err)
	}
	test.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if _, err := admin.DeleteTopic(cleanup, topic); err != nil {
			test.Error(err)
		}
	})
	if created.NumPartitions != 1 || created.ReplicationFactor != 3 {
		test.Fatalf("unexpected replicated topic configuration: %+v", created)
	}
	for name, wanted := range map[string]string{"min.insync.replicas": "2", "unclean.leader.election.enable": "false"} {
		config := created.Configs[name]
		if config.Value == nil || *config.Value != wanted {
			test.Fatalf("topic configuration %s was not applied: %+v", name, config)
		}
	}
	initial, err := waitForOutboxReplicaPartition(ctx, admin, topic, 3, -1)
	if err != nil {
		test.Fatal(err)
	}
	leader, exists := targets[initial.Leader]
	if !exists {
		test.Fatalf("elected leader %d is not one of the guarded containers", initial.Leader)
	}
	needsRestore := false
	test.Cleanup(func() {
		if !needsRestore {
			return
		}
		cleanup, cancel := context.WithTimeout(context.Background(), time.Minute)
		defer cancel()
		state, err := inspectOutboxKafkaContainer(cleanup, leader.ID)
		if err != nil {
			test.Error(err)
			return
		}
		if !state.Running {
			if err := controlOutboxKafkaContainer(cleanup, "start", leader.ID); err != nil {
				test.Error(err)
				return
			}
		}
		if _, err := waitForOutboxReplicaPartition(cleanup, admin, topic, 3, -1); err != nil {
			test.Error(err)
		}
	})
	publisher, err := newKafkaOutboxPublisher(brokers, topic, 15*time.Second)
	if err != nil {
		test.Fatal(err)
	}
	defer publisher.close()
	rows := []outboxRelayRow{
		kafkaOutboxTestRow(test, "acknowledged-before-leader-loss", "ride-1", 0),
		kafkaOutboxTestRow(test, "published-with-leader-stopped", "ride-1", 1),
		kafkaOutboxTestRow(test, "continued-on-new-leader", "ride-1", 2),
	}
	if err := publisher.publish(ctx, rows[0]); err != nil {
		test.Fatal(err)
	}
	needsRestore = true
	if err := controlOutboxKafkaContainer(ctx, "stop", leader.ID); err != nil {
		test.Fatal(err)
	}
	state, err := inspectOutboxKafkaContainer(ctx, leader.ID)
	if err != nil || state.Running {
		test.Fatalf("original leader did not stop: state=%+v error=%v", state, err)
	}
	elected, err := waitForOutboxReplicaPartition(ctx, admin, topic, 2, initial.Leader)
	if err != nil {
		test.Fatal(err)
	}
	var prefix bytes.Buffer
	if _, err := captureOutboxKafkaTrace(ctx, brokers, topic, &prefix); err != nil {
		test.Fatal(err)
	}
	verification, err := verifyOutboxTrace(bytes.NewReader(rows[0].envelope), &prefix)
	if err != nil || !verification.ok() || verification.Unique != 1 || verification.Duplicates != 0 {
		test.Fatalf("acknowledged prefix lost during election: verification=%+v error=%v", verification, err)
	}
	for _, row := range rows[1:] {
		if err := publisher.publish(ctx, row); err != nil {
			test.Fatal(err)
		}
	}
	state, err = inspectOutboxKafkaContainer(ctx, leader.ID)
	if err != nil || state.Running {
		test.Fatalf("publication did not happen with the original leader stopped: state=%+v error=%v", state, err)
	}
	var expected, received bytes.Buffer
	for _, row := range rows {
		expected.Write(row.envelope)
		expected.WriteByte('\n')
	}
	if _, err := captureOutboxKafkaTrace(ctx, brokers, topic, &received); err != nil {
		test.Fatal(err)
	}
	verification, err = verifyOutboxTrace(&expected, &received)
	if err != nil || !verification.ok() || verification.Unique != 3 || verification.Duplicates != 0 {
		test.Fatalf("replicated delivery verification=%+v error=%v", verification, err)
	}
	test.Logf("replicated failover: leader %d -> %d, ISR=%v, unique=%d, missing=%d, order_violations=%d",
		initial.Leader, elected.Leader, elected.ISR, verification.Unique, verification.Missing, verification.OrderViolations)
}
