package com.ridehailing.platform;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Valkey (ADR-020, LLD §9.4): one multiplexed Lettuce connection to a single node or a cluster, present when
 * {@code ride.location.store=valkey}. Every wait has its own timeout, and a failure is a Lettuce
 * {@code RedisException}, counted in {@code valkey_errors_total{operation}}.
 */
public interface Valkey {

    /** The connection's commands, sent at once and pipelined; wait for their results with {@link #await}. */
    RedisClusterAsyncCommands<String, String> commands();

    Timeouts timeouts();

    /** Loads the scripts on every primary, so their first calls needn't send them. */
    void load(Script... scripts);

    /**
     * Runs a script by {@code EVALSHA}, repeated once with {@code EVAL} if the node doesn't have it. All keys must
     * share one hash tag.
     */
    <T> T run(Script script, ScriptOutputType output, Duration timeout, String[] keys, String... args);

    /** The values of commands sent together, in order, waiting at most the timeout for all of them. */
    <T> List<T> await(String operation, Duration timeout, List<RedisFuture<T>> futures);

    default <T> T await(String operation, Duration timeout, RedisFuture<T> future) {
        return await(operation, timeout, List.of(future)).getFirst();
    }

    /** {@code ride.valkey.timeouts}: dispatch queries, mirror writes, location updates, and everything else. */
    record Timeouts(Duration query, Duration mirror, Duration update, Duration other) {
    }

    /** A Lua script from {@code valkey/<name>.lua} on the classpath, with its SHA-1 for {@code EVALSHA}. */
    record Script(String name, String source, String sha) {

        public static Script named(String name) {
            String path = "valkey/" + name + ".lua";
            try (InputStream in = Valkey.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("No script " + path + " on the classpath");
                }
                String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return new Script(name, source, HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-1").digest(source.getBytes(StandardCharsets.UTF_8))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
