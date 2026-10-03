package com.ridehailing.ride.app;

import com.ridehailing.platform.TimerKind;

/** The ride's timers (LLD §5.4). */
enum RideTimers implements TimerKind {
    /** Payload {@code generation}: the search it belongs to (§7.3). */
    SEARCH_TIMEOUT
}
