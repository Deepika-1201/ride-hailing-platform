package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.dispatch.db.AvailabilityRepository.AvailabilityRow;
import com.ridehailing.dispatch.db.DecisionRepository;
import com.ridehailing.dispatch.db.DriverStatsRepository;
import com.ridehailing.dispatch.db.DriverStatsRepository.Stat;
import com.ridehailing.dispatch.db.OfferRepository;
import com.ridehailing.dispatch.db.OfferRepository.OfferRow;
import com.ridehailing.dispatch.db.SearchTaskRepository;
import com.ridehailing.dispatch.db.SearchTaskRepository.TaskRow;
import com.ridehailing.dispatch.events.OfferCreated;
import com.ridehailing.geography.GeographyApi;
import com.ridehailing.geography.GeographyApi.CategorySettings;
import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LiveIndex.Candidate;
import com.ridehailing.platform.Outbox;
import com.ridehailing.platform.Timers;
import com.ridehailing.platform.Transactions;
import com.ridehailing.ride.RideAssignment;
import com.ridehailing.ride.RideAssignment.SearchingRide;
import com.ridehailing.shared.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * One search attempt per transaction (LLD §8.3): claim a due task, hold the ride {@code FOR SHARE}, rank the nearby
 * candidates not offered this ride before, and reserve the first one still available, trying at most five.
 */
@Service
class SearchAttempts {

    static final int MAX_INDEX_BACKOFF_S = 10;
    private static final Logger log = LoggerFactory.getLogger(SearchAttempts.class);

    private final SearchTaskRepository tasks;
    private final RideAssignment rides;
    private final OfferRepository offers;
    private final AvailabilityRepository availability;
    private final DecisionRepository decisions;
    private final DriverStatsRepository stats;
    private final GeographyApi geography;
    private final LiveIndex index;
    private final Rankers rankers;
    private final Timers timers;
    private final Outbox outbox;
    private final LiveIndexMirror mirror;
    private final Transactions transactions;
    private final DispatchProperties properties;
    private final DispatchMetrics metrics;
    private final JsonMapper json;
    private final Clock clock;

    SearchAttempts(SearchTaskRepository tasks, RideAssignment rides, OfferRepository offers,
            AvailabilityRepository availability, DecisionRepository decisions, DriverStatsRepository stats,
            GeographyApi geography, LiveIndex index, Rankers rankers, Timers timers, Outbox outbox,
            LiveIndexMirror mirror, Transactions transactions, DispatchProperties properties, DispatchMetrics metrics,
            JsonMapper json, Clock clock) {
        this.tasks = tasks;
        this.rides = rides;
        this.offers = offers;
        this.availability = availability;
        this.decisions = decisions;
        this.stats = stats;
        this.geography = geography;
        this.index = index;
        this.rankers = rankers;
        this.timers = timers;
        this.outbox = outbox;
        this.mirror = mirror;
        this.transactions = transactions;
        this.properties = properties;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    /** Runs the attempt of the most urgent due task; answers whether there was one. */
    boolean runNext() {
        return transactions.execute(() -> {
            long started = System.nanoTime();
            TaskRow task = tasks.claimDue().orElse(null);
            if (task == null) {
                return false;
            }
            SearchingRide ride = rides.lockIfSearching(task.rideId()).orElse(null);
            if (ride == null) {
                tasks.delete(task.rideId());
                return true;
            }
            attempt(task, ride, started);
            return true;
        });
    }

    private void attempt(TaskRow task, SearchingRide ride, long started) {
        CategorySettings settings = geography.settings(task.cityId(), task.category()).orElseThrow();
        CandidateRanker ranker = rankers.named(settings.ranker());
        int attempt = task.attempt() + 1;
        Set<UUID> offeredBefore = offers.driversOffered(task.rideId());
        List<Candidate> nearby;
        long indexStarted = System.nanoTime();
        try {
            nearby = index.nearby(task.cityId(), task.category(), task.pickup(), task.radiusM(),
                    properties.candidates());
        } catch (RuntimeException e) {
            int backoff = task.backoffS() == 0 ? 1 : Math.min(task.backoffS() * 2, MAX_INDEX_BACKOFF_S);
            log.warn("The live index failed for ride {}; searching again in {} s", task.rideId(), backoff, e);
            tasks.backOff(task.rideId(), backoff);
            decide(task, attempt, ranker, "INDEX_UNAVAILABLE", null, null, List.of(), 0, List.of(), started,
                    Double.NaN);
            return;
        }
        double indexMs = (System.nanoTime() - indexStarted) / 1e6;
        List<Candidate> eligible = nearby.stream().filter(candidate -> !offeredBefore.contains(candidate.driverId()))
                .toList();
        List<CandidateRanker.Ranked> ranked = ranker.rank(eligible);
        List<Try> tries = new ArrayList<>();
        for (int position = 0; position < Math.min(ranked.size(), properties.maxReservationTries()); position++) {
            Candidate candidate = ranked.get(position).candidate();
            UUID offerId = Ids.newId();
            AvailabilityRow reserved = availability.reserve(candidate.driverId(), offerId, task.cityId(),
                    task.category()).orElse(null);
            if (reserved == null) {
                tries.add(new Try(candidate.driverId(), "LOST"));
                continue;
            }
            tries.add(new Try(candidate.driverId(), "RESERVED"));
            OfferRow offer = offers.insert(offerId, task.rideId(), candidate.driverId(), attempt, position + 1,
                    candidate.distanceM(), settings.offerTtlS());
            timers.schedule(DispatchTimers.OFFER_EXPIRY, offerId, offer.expiresAt(), Map.of());
            tasks.pause(task.rideId());
            decide(task, attempt, ranker, "OFFERED", candidate.driverId(), offerId, ranked,
                    nearby.size() - eligible.size(), tries, started, indexMs);
            outbox.append(OfferEndings.event(OfferCreated.TYPE, OfferCreated.VERSION, offer, new OfferCreated(offerId,
                    task.rideId(), candidate.driverId(), attempt, position + 1, candidate.distanceM(), ranker.name(),
                    offer.expiresAt())));
            stats.count(candidate.driverId(), Stat.OFFERS);
            if (offeredBefore.isEmpty()) {
                metrics.firstOffer(task.cityId(), Duration.between(ride.requestedAt(), offer.createdAt()));
            }
            mirror.afterCommit(reserved);
            return;
        }
        boolean lostRaces = !tries.isEmpty();
        tasks.retryLater(task.rideId(), Math.min(task.radiusM() + settings.radiusStepM(), settings.radiusMaxM()),
                lostRaces ? properties.contentionRetryAfter() : properties.retryAfter());
        decide(task, attempt, ranker, lostRaces ? "ALL_RESERVATIONS_LOST" : "NO_CANDIDATES", null, null, ranked,
                nearby.size() - eligible.size(), tries, started, indexMs);
    }

    private void decide(TaskRow task, int attempt, CandidateRanker ranker, String outcome, UUID chosenDriverId,
            UUID offerId, List<CandidateRanker.Ranked> ranked, int alreadyOffered, List<Try> tries, long started,
            double indexMs) {
        Instant now = clock.instant();
        List<CandidateDetail> candidates = ranked.stream().map(entry -> new CandidateDetail(
                entry.candidate().driverId(), entry.candidate().distanceM(),
                Duration.between(entry.candidate().lastSeen(), now).toSeconds(), entry.score())).toList();
        String detail = json.writeValueAsString(new Detail(candidates, Map.of("already_offered", alreadyOffered),
                tries, Double.isNaN(indexMs) ? null : indexMs));
        decisions.insert(Ids.newId(), task.rideId(), attempt, ranker.name(), ranker.version(), task.radiusM(), outcome,
                chosenDriverId, offerId, detail, (System.nanoTime() - started) / 1_000);
        metrics.attempted(outcome, tries.stream().filter(tried -> tried.result().equals("LOST")).count());
    }

    /** The decision record's {@code detail} (§8.3); {@code indexMs} is null when the index failed. */
    record Detail(List<CandidateDetail> candidates, Map<String, Integer> excluded, List<Try> tries, Double indexMs) {
    }

    record CandidateDetail(UUID driverId, int distanceM, long seenAgoS, double score) {
    }

    record Try(UUID driverId, String result) {
    }
}
