package com.ridehailing.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.shared.GeoPoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NearestDriverRankerTests {

    private static final GeoPoint SOMEWHERE = new GeoPoint(12.97, 77.59);
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    private final NearestDriverRanker ranker = new NearestDriverRanker();

    @Test
    void nearerDriversRankFirst() {
        Candidate far = candidate("00000000-0000-0000-0000-000000000001", 900, NOW);
        Candidate near = candidate("00000000-0000-0000-0000-000000000002", 100, NOW);

        assertThat(order(far, near)).containsExactly(near, far);
    }

    @Test
    void atTheSameDistanceTheFresherPositionWins() {
        Candidate stale = candidate("00000000-0000-0000-0000-000000000001", 300, NOW.minusSeconds(20));
        Candidate fresh = candidate("00000000-0000-0000-0000-000000000002", 300, NOW);

        assertThat(order(stale, fresh)).containsExactly(fresh, stale);
    }

    @Test
    void fullTiesGoToTheLowerDriverId() {
        Candidate higher = candidate("00000000-0000-0000-0000-000000000009", 300, NOW);
        Candidate lower = candidate("00000000-0000-0000-0000-000000000003", 300, NOW);

        assertThat(order(higher, lower)).containsExactly(lower, higher);
    }

    @Test
    void theScoreIsTheDistance() {
        Candidate candidate = candidate("00000000-0000-0000-0000-000000000001", 450, NOW);

        assertThat(ranker.rank(List.of(candidate))).singleElement()
                .satisfies(ranked -> assertThat(ranked.score()).isEqualTo(450.0));
    }

    private List<Candidate> order(Candidate... candidates) {
        return ranker.rank(List.of(candidates)).stream().map(CandidateRanker.Ranked::candidate).toList();
    }

    private static Candidate candidate(String driverId, int distanceM, Instant lastSeen) {
        return new Candidate(UUID.fromString(driverId), SOMEWHERE, distanceM, lastSeen);
    }
}
