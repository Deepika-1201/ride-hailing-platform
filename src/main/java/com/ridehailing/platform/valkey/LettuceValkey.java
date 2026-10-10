package com.ridehailing.platform.valkey;

import com.ridehailing.platform.Valkey;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandInterruptedException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * {@link Valkey} on one Lettuce connection (ADR-020). While the connection is down, commands fail at once rather
 * than queue for the reconnect; a cluster connection follows the topology on {@code MOVED}, {@code ASK} and
 * reconnects, and every 30 s.
 */
public final class LettuceValkey implements Valkey, AutoCloseable {

    /** Lettuce drops a command left unanswered this long, so a stalled server can't pile them up. */
    private static final Duration COMMAND_EXPIRY = Duration.ofSeconds(1);
    private static final Duration TOPOLOGY_REFRESH = Duration.ofSeconds(30);
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(5);

    private final AbstractRedisClient client;
    private final StatefulConnection<String, String> connection;
    private final RedisClusterAsyncCommands<String, String> commands;
    private final Supplier<List<RedisClusterAsyncCommands<String, String>>> primaries;
    private final Timeouts timeouts;
    private final MeterRegistry meters;

    private LettuceValkey(AbstractRedisClient client, StatefulConnection<String, String> connection,
            RedisClusterAsyncCommands<String, String> commands,
            Supplier<List<RedisClusterAsyncCommands<String, String>>> primaries, Timeouts timeouts,
            MeterRegistry meters) {
        this.client = client;
        this.connection = connection;
        this.commands = commands;
        this.primaries = primaries;
        this.timeouts = timeouts;
        this.meters = meters;
    }

    public static LettuceValkey connect(ValkeyProperties properties, MeterRegistry meters) {
        RedisURI uri = RedisURI.create(properties.uri());
        Timeouts timeouts = properties.timeouts().toValkey();
        return properties.cluster()
                ? cluster(RedisClusterClient.create(uri), timeouts, meters)
                : standalone(RedisClient.create(uri), timeouts, meters);
    }

    public static LettuceValkey standalone(RedisClient client, Timeouts timeouts, MeterRegistry meters) {
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(COMMAND_EXPIRY))
                .build());
        StatefulRedisConnection<String, String> connection = client.connect(StringCodec.UTF8);
        return new LettuceValkey(client, connection, connection.async(), () -> List.of(connection.async()), timeouts,
                meters);
    }

    public static LettuceValkey cluster(RedisClusterClient client, Timeouts timeouts, MeterRegistry meters) {
        client.setOptions(ClusterClientOptions.builder()
                // Lettuce 7 refreshes on MOVED, ASK and reconnects by default (its adaptive triggers).
                .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                        .enablePeriodicRefresh(TOPOLOGY_REFRESH)
                        .build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(COMMAND_EXPIRY))
                .build());
        StatefulRedisClusterConnection<String, String> connection = client.connect(StringCodec.UTF8);
        return new LettuceValkey(client, connection, connection.async(), () -> connection.getPartitions().stream()
                .filter(node -> node.is(RedisClusterNode.NodeFlag.UPSTREAM))
                .<RedisClusterAsyncCommands<String, String>>map(node -> connection.getConnection(node.getNodeId())
                        .async())
                .toList(), timeouts, meters);
    }

    @Override
    public RedisClusterAsyncCommands<String, String> commands() {
        return commands;
    }

    @Override
    public Timeouts timeouts() {
        return timeouts;
    }

    @Override
    public void load(Script... scripts) {
        for (RedisClusterAsyncCommands<String, String> primary : primaries()) {
            await("script_load", LOAD_TIMEOUT, Arrays.stream(scripts).map(script -> primary.scriptLoad(script.source()))
                    .toList());
        }
    }

    /** The node, or each primary of the cluster. */
    List<RedisClusterAsyncCommands<String, String>> primaries() {
        return primaries.get();
    }

    public boolean isCluster() {
        return client instanceof RedisClusterClient;
    }

    /** A connection of its own for subscriptions, which the command connection can't take; the caller closes it. */
    public StatefulRedisPubSubConnection<String, String> connectPubSub() {
        return client instanceof RedisClusterClient cluster ? cluster.connectPubSub(StringCodec.UTF8)
                : ((RedisClient) client).connectPubSub(StringCodec.UTF8);
    }

    @Override
    public <T> T run(Script script, ScriptOutputType output, Duration timeout, String[] keys, String... args) {
        try {
            return get(timeout, commands.<T>evalsha(script.sha(), output, keys, args));
        } catch (RedisNoScriptException e) {
            // A node that restarted, or took over after the scripts were loaded, lacks it; EVAL caches it there.
        } catch (RedisException e) {
            throw failed(script.name(), e);
        }
        try {
            return get(timeout, commands.<T>eval(script.source(), output, keys, args));
        } catch (RedisException e) {
            throw failed(script.name(), e);
        }
    }

    @Override
    public <T> List<T> await(String operation, Duration timeout, List<RedisFuture<T>> futures) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<T> values = new ArrayList<>(futures.size());
        try {
            for (RedisFuture<T> future : futures) {
                values.add(get(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())), future));
            }
        } catch (RedisException e) {
            throw failed(operation, e);
        }
        return values;
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }

    /** For a command whose result the caller doesn't wait for, such as a push. */
    public void countError(String operation) {
        meters.counter("valkey.errors", "operation", operation).increment();
    }

    private static <T> T get(Duration timeout, RedisFuture<T> future) {
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new RedisCommandTimeoutException("No answer from Valkey within " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RedisException redis ? redis : new RedisException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedisCommandInterruptedException(e);
        }
    }

    private RedisException failed(String operation, RedisException e) {
        countError(operation);
        return e;
    }
}
