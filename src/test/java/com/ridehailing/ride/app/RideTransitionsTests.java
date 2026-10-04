package com.ridehailing.ride.app;

import static com.ridehailing.ride.RideStatus.CANCELLED_BY_DRIVER;
import static com.ridehailing.ride.RideStatus.CANCELLED_BY_RIDER;
import static com.ridehailing.ride.RideStatus.CANCELLED_BY_SYSTEM;
import static com.ridehailing.ride.RideStatus.COMPLETED;
import static com.ridehailing.ride.RideStatus.DRIVER_ARRIVED;
import static com.ridehailing.ride.RideStatus.DRIVER_ASSIGNED;
import static com.ridehailing.ride.RideStatus.DRIVER_NOT_FOUND;
import static com.ridehailing.ride.RideStatus.IN_TRIP;
import static com.ridehailing.ride.RideStatus.SEARCHING;
import static com.ridehailing.ride.app.RideTransitions.Command.ACCEPT;
import static com.ridehailing.ride.app.RideTransitions.Command.ARRIVE;
import static com.ridehailing.ride.app.RideTransitions.Command.BOOK;
import static com.ridehailing.ride.app.RideTransitions.Command.CANCEL;
import static com.ridehailing.ride.app.RideTransitions.Command.COMPLETE;
import static com.ridehailing.ride.app.RideTransitions.Command.NO_SHOW;
import static com.ridehailing.ride.app.RideTransitions.Command.SEARCH_TIMEOUT;
import static com.ridehailing.ride.app.RideTransitions.Command.START;
import static com.ridehailing.ride.app.RideTransitions.Command.UNREACHABLE;
import static com.ridehailing.shared.Actor.Type.ADMIN;
import static com.ridehailing.shared.Actor.Type.DRIVER;
import static com.ridehailing.shared.Actor.Type.OPS;
import static com.ridehailing.shared.Actor.Type.RIDER;
import static com.ridehailing.shared.Actor.Type.SYSTEM;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.app.RideTransitions.Command;
import com.ridehailing.ride.app.RideTransitions.Transition;
import com.ridehailing.ride.db.TransitionRepository.Allowed;
import com.ridehailing.shared.Actor;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The transition table is ride lifecycle §3 (LLD §7.1): T1–T13, with T8 from two states and T13 from four. */
class RideTransitionsTests {

    @Test
    void theTableIsTheLifecyclesThirteenTransitions() {
        assertThat(RideTransitions.TABLE).containsExactlyInAnyOrder(
                new Transition(null, BOOK, RIDER, SEARCHING),
                new Transition(SEARCHING, ACCEPT, DRIVER, DRIVER_ASSIGNED),
                new Transition(SEARCHING, SEARCH_TIMEOUT, SYSTEM, DRIVER_NOT_FOUND),
                new Transition(SEARCHING, CANCEL, RIDER, CANCELLED_BY_RIDER),
                new Transition(DRIVER_ASSIGNED, ARRIVE, DRIVER, DRIVER_ARRIVED),
                new Transition(DRIVER_ASSIGNED, CANCEL, DRIVER, SEARCHING),
                new Transition(DRIVER_ASSIGNED, UNREACHABLE, SYSTEM, SEARCHING),
                new Transition(DRIVER_ASSIGNED, CANCEL, RIDER, CANCELLED_BY_RIDER),
                new Transition(DRIVER_ARRIVED, CANCEL, RIDER, CANCELLED_BY_RIDER),
                new Transition(DRIVER_ARRIVED, START, DRIVER, IN_TRIP),
                new Transition(DRIVER_ARRIVED, NO_SHOW, DRIVER, CANCELLED_BY_DRIVER),
                new Transition(DRIVER_ARRIVED, CANCEL, DRIVER, CANCELLED_BY_DRIVER),
                new Transition(IN_TRIP, COMPLETE, DRIVER, COMPLETED),
                new Transition(SEARCHING, CANCEL, OPS, CANCELLED_BY_SYSTEM),
                new Transition(DRIVER_ASSIGNED, CANCEL, OPS, CANCELLED_BY_SYSTEM),
                new Transition(DRIVER_ARRIVED, CANCEL, OPS, CANCELLED_BY_SYSTEM),
                new Transition(IN_TRIP, CANCEL, OPS, CANCELLED_BY_SYSTEM),
                new Transition(SEARCHING, CANCEL, ADMIN, CANCELLED_BY_SYSTEM),
                new Transition(DRIVER_ASSIGNED, CANCEL, ADMIN, CANCELLED_BY_SYSTEM),
                new Transition(DRIVER_ARRIVED, CANCEL, ADMIN, CANCELLED_BY_SYSTEM),
                new Transition(IN_TRIP, CANCEL, ADMIN, CANCELLED_BY_SYSTEM));
    }

    @Test
    void eachCommandOfAnActorLeadsOneWayFromAState() {
        assertThat(RideTransitions.TABLE.stream()
                .collect(Collectors.groupingBy(row -> row.from() + " " + row.command() + " " + row.actor(),
                        Collectors.counting())))
                .allSatisfy((key, rows) -> assertThat(rows).as(key).isEqualTo(1));
    }

    @Test
    void nothingLeavesAnEndedRide() {
        assertThat(RideTransitions.TABLE).noneMatch(row -> row.from() != null && !row.from().active());
        assertThat(Arrays.stream(RideStatus.values()).filter(status -> !status.active()))
                .allSatisfy(ended -> assertThat(RideTransitions.TABLE).anyMatch(row -> row.to() == ended));
    }

    @Test
    void operationsCanEndEveryActiveRideAndTheRiderNoneInTrip() {
        for (RideStatus active : List.of(SEARCHING, DRIVER_ASSIGNED, DRIVER_ARRIVED, IN_TRIP)) {
            for (Actor.Type ops : List.of(OPS, ADMIN)) {
                assertThat(RideTransitions.target(active, CANCEL, ops)).as("%s by %s", active, ops)
                        .hasValue(CANCELLED_BY_SYSTEM);
            }
        }
        assertThat(RideTransitions.target(IN_TRIP, CANCEL, RIDER)).isEmpty();
        assertThat(RideTransitions.target(SEARCHING, CANCEL, DRIVER)).isEmpty();
        assertThat(RideTransitions.target(COMPLETED, COMPLETE, DRIVER)).isEmpty();
        assertThat(RideTransitions.TABLE).noneMatch(row -> row.actor() == SYSTEM && row.command() == CANCEL);
    }

    @Test
    void theCheckReadsTheSameTable() {
        List<Allowed> allowed = RideTransitions.allowed();

        assertThat(allowed).hasSameSizeAs(RideTransitions.TABLE);
        assertThat(allowed).contains(new Allowed(null, "SEARCHING", "BOOK", "RIDER"),
                new Allowed("DRIVER_ASSIGNED", "SEARCHING", "UNREACHABLE", "SYSTEM"),
                new Allowed("IN_TRIP", "CANCELLED_BY_SYSTEM", "CANCEL", "ADMIN"));
        assertThat(allowed).extracting(Allowed::command).containsOnly(Arrays.stream(Command.values())
                .map(Command::name).toArray(String[]::new));
    }
}
