package com.ridehailing.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class IdsTests {

    @Test
    void areVersion7WithTheRfcVariant() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void carryTheCurrentUnixMillisecond() {
        long before = System.currentTimeMillis();
        long stamped = Ids.newId().getMostSignificantBits() >>> 16;
        long after = System.currentTimeMillis();

        // A burst may run the counter slightly ahead of the clock, never behind it.
        assertThat(stamped).isBetween(before, after + 1_000);
    }

    @Test
    void increaseStrictlyEvenWithinOneMillisecond() {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 100_000; index++) {
            ids.add(Ids.newId().toString());
        }

        // Lowercase hex strings sort like the unsigned 128-bit values, which is also PostgreSQL's uuid order.
        for (int index = 1; index < ids.size(); index++) {
            assertThat(ids.get(index)).isGreaterThan(ids.get(index - 1));
        }
    }

    @Test
    void areUniqueAcrossThreads() throws Exception {
        Set<UUID> seen = ConcurrentHashMap.newKeySet();
        List<Future<?>> tasks = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int thread = 0; thread < 8; thread++) {
                tasks.add(executor.submit(() -> {
                    Set<UUID> mine = new HashSet<>();
                    for (int index = 0; index < 20_000; index++) {
                        mine.add(Ids.newId());
                    }
                    seen.addAll(mine);
                }));
            }
            for (Future<?> task : tasks) {
                task.get();
            }
        }

        assertThat(seen).hasSize(160_000);
    }
}
