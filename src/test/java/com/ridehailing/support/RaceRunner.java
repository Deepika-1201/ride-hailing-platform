package com.ridehailing.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLD §17.2: starts commands on their own threads behind a barrier, so they reach the database together. Each command
 * answers its outcome as text, such as {@code 200} or {@code 409 OFFER_NO_LONGER_AVAILABLE}.
 */
public final class RaceRunner {

    private static final Duration MAX_DELAY = Duration.ofMillis(60);

    private RaceRunner() {
    }

    /** {@code ride.races.repetitions}, 200 unless set (LLD §17.2). */
    public static int repetitions() {
        return Integer.getInteger("ride.races.repetitions", 200);
    }

    /** The two outcomes, in the order of the commands; a command that throws answers {@code error: …}. */
    public static List<String> race(Callable<String> first, Callable<String> second) throws InterruptedException {
        return race(List.of(first, second));
    }

    /**
     * Like {@link #race(Callable, Callable)}, but one command, either at random, waits a random 0–60 ms after the
     * barrier. Without the wait, the command with less work before its first lock nearly always wins (an HTTP call
     * loses to a direct one), and the race never explores the other order.
     */
    public static List<String> staggered(Callable<String> first, Callable<String> second)
            throws InterruptedException {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Duration delay = Duration.ofNanos(random.nextLong(MAX_DELAY.toNanos()));
        boolean firstWaits = random.nextBoolean();
        return race(List.of(after(firstWaits ? delay : Duration.ZERO, first),
                after(firstWaits ? Duration.ZERO : delay, second)));
    }

    private static Callable<String> after(Duration delay, Callable<String> command) {
        return () -> {
            Thread.sleep(delay);
            return command.call();
        };
    }

    /**
     * With at least 100 repetitions, checks that each of the outcomes happened, so the race explored every order
     * rather than one side always winning.
     */
    public static void assertExplored(Map<String, Integer> seen, String... outcomes) {
        if (repetitions() >= 100) {
            assertThat(seen).as("outcomes over %d repetitions", repetitions()).containsKeys(outcomes);
        }
    }

    public static List<String> race(List<Callable<String>> commands) throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(commands.size());
        List<String> outcomes = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(commands.size())) {
            List<Future<String>> running = new ArrayList<>();
            for (Callable<String> command : commands) {
                running.add(threads.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return command.call();
                }));
            }
            for (Future<String> outcome : running) {
                try {
                    outcomes.add(outcome.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add("error: " + e.getCause());
                } catch (TimeoutException e) {
                    outcomes.add("error: timed out");
                }
            }
        }
        return outcomes;
    }
}
