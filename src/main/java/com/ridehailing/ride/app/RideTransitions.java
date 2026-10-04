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

import com.ridehailing.ride.RideStatus;
import com.ridehailing.ride.db.TransitionRepository.Allowed;
import com.ridehailing.shared.Actor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The transition table of ride lifecycle §3 (LLD §7.1): every command looks itself up here, and I5 checks the
 * transition log against the same rows.
 */
final class RideTransitions {

    enum Command {
        BOOK, ACCEPT, SEARCH_TIMEOUT, CANCEL, ARRIVE, START, NO_SHOW, COMPLETE, UNREACHABLE
    }

    /** {@code from} is null for the booking. */
    record Transition(RideStatus from, Command command, Actor.Type actor, RideStatus to) {
    }

    static final List<Transition> TABLE = table();

    private RideTransitions() {
    }

    /** Where the command takes the ride, or empty if the table has no such row. */
    static Optional<RideStatus> target(RideStatus from, Command command, Actor.Type actor) {
        return TABLE.stream()
                .filter(row -> row.from() == from && row.command() == command && row.actor() == actor)
                .map(Transition::to)
                .findFirst();
    }

    static List<Allowed> allowed() {
        return TABLE.stream().map(row -> new Allowed(row.from() == null ? null : row.from().name(), row.to().name(),
                row.command().name(), row.actor().name())).toList();
    }

    private static List<Transition> table() {
        List<Transition> rows = new ArrayList<>(List.of(
                new Transition(null, Command.BOOK, Actor.Type.RIDER, SEARCHING),                          // T1
                new Transition(SEARCHING, Command.ACCEPT, Actor.Type.DRIVER, DRIVER_ASSIGNED),            // T2
                new Transition(SEARCHING, Command.SEARCH_TIMEOUT, Actor.Type.SYSTEM, DRIVER_NOT_FOUND),   // T3
                new Transition(SEARCHING, Command.CANCEL, Actor.Type.RIDER, CANCELLED_BY_RIDER),          // T4
                new Transition(DRIVER_ASSIGNED, Command.ARRIVE, Actor.Type.DRIVER, DRIVER_ARRIVED),       // T5
                new Transition(DRIVER_ASSIGNED, Command.CANCEL, Actor.Type.DRIVER, SEARCHING),            // T6
                new Transition(DRIVER_ASSIGNED, Command.UNREACHABLE, Actor.Type.SYSTEM, SEARCHING),       // T7
                new Transition(DRIVER_ASSIGNED, Command.CANCEL, Actor.Type.RIDER, CANCELLED_BY_RIDER),    // T8
                new Transition(DRIVER_ARRIVED, Command.CANCEL, Actor.Type.RIDER, CANCELLED_BY_RIDER),     // T8
                new Transition(DRIVER_ARRIVED, Command.START, Actor.Type.DRIVER, IN_TRIP),                // T9
                new Transition(DRIVER_ARRIVED, Command.NO_SHOW, Actor.Type.DRIVER, CANCELLED_BY_DRIVER),  // T10
                new Transition(DRIVER_ARRIVED, Command.CANCEL, Actor.Type.DRIVER, CANCELLED_BY_DRIVER),   // T11
                new Transition(IN_TRIP, Command.COMPLETE, Actor.Type.DRIVER, COMPLETED)));                // T12
        for (RideStatus from : List.of(SEARCHING, DRIVER_ASSIGNED, DRIVER_ARRIVED, IN_TRIP)) {                   // T13
            rows.add(new Transition(from, Command.CANCEL, Actor.Type.OPS, CANCELLED_BY_SYSTEM));
            rows.add(new Transition(from, Command.CANCEL, Actor.Type.ADMIN, CANCELLED_BY_SYSTEM));
        }
        return List.copyOf(rows);
    }
}
