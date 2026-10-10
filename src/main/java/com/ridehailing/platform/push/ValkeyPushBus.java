package com.ridehailing.platform.push;

import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.valkey.LettuceValkey;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubAdapter;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pushes through Valkey (LLD §14.7): {@code PUBLISH} and {@code SUBSCRIBE} on a single node; in a cluster the sharded
 * {@code SPUBLISH} and {@code SSUBSCRIBE}, so each message goes to its channel's shard only. Subscriptions use a
 * connection of their own, which Lettuce subscribes again after a reconnect.
 */
@Component
@ConditionalOnProperty(name = "ride.location.store", havingValue = "valkey")
class ValkeyPushBus implements PushBus, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ValkeyPushBus.class);

    private final LettuceValkey valkey;
    private final JsonMapper json;
    private final boolean sharded;
    private final Receivers receivers = new Receivers();
    private final StatefulRedisPubSubConnection<String, String> connection;
    private final RedisPubSubAsyncCommands<String, String> subscriptions;

    ValkeyPushBus(LettuceValkey valkey, JsonMapper json) {
        this.valkey = valkey;
        this.json = json;
        this.sharded = valkey.isCluster();
        this.connection = valkey.connectPubSub();
        if (connection instanceof StatefulRedisClusterPubSubConnection<String, String> cluster) {
            cluster.addListener(new RedisClusterPubSubAdapter<>() {
                @Override
                public void smessage(RedisClusterNode node, String channel, String message) {
                    receivers.deliver(channel, message);
                }
            });
        } else {
            connection.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    receivers.deliver(channel, message);
                }
            });
        }
        this.subscriptions = connection.async();
    }

    @Override
    public void publish(String channel, Object message) {
        try {
            String text = json.writeValueAsString(message);
            RedisFuture<Long> sent = sharded ? valkey.commands().spublish(channel, text)
                    : valkey.commands().publish(channel, text);
            sent.whenComplete((reached, failure) -> {
                if (failure != null) {
                    failed(channel, failure);
                }
            });
        } catch (RuntimeException e) {
            failed(channel, e);
        }
    }

    /** Waits for the node's subscription when this receiver is the channel's first; throws if Valkey refuses it. */
    @Override
    public Subscription subscribe(String channel, Consumer<String> receiver) {
        AtomicReference<RedisFuture<Void>> subscribing = new AtomicReference<>();
        receivers.add(channel, receiver, () -> subscribing.set(sharded ? subscriptions.ssubscribe(channel)
                : subscriptions.subscribe(channel)));
        Subscription subscription = receivers.subscription(channel, receiver, () -> {
            RedisFuture<Void> leaving = sharded ? subscriptions.sunsubscribe(channel)
                    : subscriptions.unsubscribe(channel);
            leaving.whenComplete((done, failure) -> {
                if (failure != null) {
                    log.warn("Unsubscribing from {} failed: {}", channel, failure.toString());
                }
            });
        });
        if (subscribing.get() != null) {
            try {
                valkey.await("subscribe", valkey.timeouts().other(), subscribing.get());
            } catch (RuntimeException e) {
                subscription.close();
                throw e;
            }
        }
        return subscription;
    }

    @Override
    public void close() {
        connection.close();
    }

    private void failed(String channel, Throwable failure) {
        valkey.countError("publish");
        log.debug("Pushing to {} failed: {}", channel, failure.toString());
    }
}
