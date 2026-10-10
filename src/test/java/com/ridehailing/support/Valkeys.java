package com.ridehailing.support;

import com.github.dockerjava.api.DockerClient;
import com.ridehailing.platform.Valkey;
import com.ridehailing.platform.valkey.LettuceValkey;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.internal.HostAndPort;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.MappingSocketAddressResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Valkey 8 for tests (LLD §17.1): a single node and a 3-shard cluster, each started once per JVM and shared, since
 * every test uses keys of its own. Testcontainers removes them when the JVM exits.
 */
public final class Valkeys {

    /** Generous, so a slow runner doesn't fail a test that isn't about timeouts. */
    public static final Valkey.Timeouts TIMEOUTS = new Valkey.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(5), Duration.ofSeconds(5));

    private static final DockerImageName IMAGE = DockerImageName.parse("valkey/valkey:8");
    private static final int PORT = 6379;
    private static final List<Integer> CLUSTER_PORTS = List.of(7000, 7001, 7002);
    private static final MeterRegistry METERS = new SimpleMeterRegistry();

    private static GenericContainer<?> node;
    private static GenericContainer<?> clusterNodes;
    private static ClientResources resources;
    private static LettuceValkey standalone;
    private static LettuceValkey cluster;

    private Valkeys() {
    }

    public static synchronized GenericContainer<?> node() {
        if (node == null) {
            node = new GenericContainer<>(IMAGE).withExposedPorts(PORT)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
            node.start();
        }
        return node;
    }

    public static String uri() {
        return "redis://" + node().getHost() + ":" + node().getMappedPort(PORT);
    }

    /** The shared client of the single node; its errors count in {@link #meters()}. */
    public static synchronized LettuceValkey standalone() {
        if (standalone == null) {
            standalone = newStandalone(TIMEOUTS);
        }
        return standalone;
    }

    /** A client of its own, for tests of timeouts; the caller closes it. */
    public static LettuceValkey newStandalone(Valkey.Timeouts timeouts) {
        return LettuceValkey.standalone(RedisClient.create(resources(), uri()), timeouts, METERS);
    }

    /**
     * The shared client of the cluster: three primaries in one container, which announce {@code 127.0.0.1:700x};
     * the client maps those to the container's ports.
     */
    public static synchronized LettuceValkey cluster() {
        if (cluster == null) {
            GenericContainer<?> container = clusterNodes();
            Function<HostAndPort, HostAndPort> toMappedPort = address -> CLUSTER_PORTS.contains(address.getPort())
                    ? HostAndPort.of(container.getHost(), container.getMappedPort(address.getPort()))
                    : address;
            ClientResources mapped = ClientResources.builder()
                    .socketAddressResolver(MappingSocketAddressResolver.create(toMappedPort))
                    .build();
            cluster = LettuceValkey.cluster(RedisClusterClient.create(mapped, RedisURI.create(container.getHost(),
                    container.getMappedPort(CLUSTER_PORTS.getFirst()))), TIMEOUTS, METERS);
        }
        return cluster;
    }

    public static MeterRegistry meters() {
        return METERS;
    }

    /** {@code valkey_errors_total} for the operation so far. */
    public static double errors(String operation) {
        Counter counter = METERS.find("valkey.errors").tag("operation", operation).counter();
        return counter == null ? 0 : counter.count();
    }

    /** Runs the work with the single node paused, so every call to it times out; resumes the node whatever happens. */
    public static void whilePaused(Runnable work) {
        DockerClient docker = node().getDockerClient();
        docker.pauseContainerCmd(node().getContainerId()).exec();
        try {
            work.run();
        } finally {
            docker.unpauseContainerCmd(node().getContainerId()).exec();
        }
    }

    private static synchronized ClientResources resources() {
        if (resources == null) {
            resources = DefaultClientResources.create();
        }
        return resources;
    }

    private static synchronized GenericContainer<?> clusterNodes() {
        if (clusterNodes == null) {
            GenericContainer<?> container = new GenericContainer<>(IMAGE)
                    .withExposedPorts(CLUSTER_PORTS.toArray(Integer[]::new))
                    .withCommand("sh", "-c", """
                            set -eu
                            for port in 7000 7001 7002; do
                              valkey-server --port $port --cluster-enabled yes --cluster-config-file nodes-$port.conf \
                                --protected-mode no --save '' --appendonly no --daemonize yes --dir /tmp
                            done
                            exec tail -f /dev/null
                            """)
                    .waitingFor(Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofMinutes(1)));
            try {
                container.start();
                ExecResult result = container.execInContainer("valkey-cli", "--cluster", "create",
                        "127.0.0.1:7000", "127.0.0.1:7001", "127.0.0.1:7002", "--cluster-replicas", "0",
                        "--cluster-yes");
                if (result.getExitCode() != 0) {
                    throw new IllegalStateException("Creating the Valkey cluster failed: " + result.getStdout()
                            + result.getStderr());
                }
                awaitClusterOk(container);
                clusterNodes = container;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                if (clusterNodes == null) {
                    container.stop();
                }
            }
        }
        return clusterNodes;
    }

    /** Every node agrees the cluster is up before the first client connects. */
    private static void awaitClusterOk(GenericContainer<?> container) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        for (int port : CLUSTER_PORTS) {
            while (!clusterOk(container, port)) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("The Valkey cluster didn't come up");
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static boolean clusterOk(GenericContainer<?> container, int port) {
        try {
            return container.execInContainer("valkey-cli", "-p", Integer.toString(port), "cluster", "info")
                    .getStdout().contains("cluster_state:ok");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
