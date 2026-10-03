package com.ridehailing.dispatch.app;

import com.ridehailing.platform.TimerKind;

/** Dispatch's timers (LLD §5.4). */
enum DispatchTimers implements TimerKind {
    /** Fires at the offer's {@code expires_at} (§8.6). */
    OFFER_EXPIRY
}
