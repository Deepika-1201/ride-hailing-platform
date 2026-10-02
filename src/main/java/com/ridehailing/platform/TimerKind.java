package com.ridehailing.platform;

/** A kind of timer, such as {@code OFFER_EXPIRY}; usually an enum constant in the owning module. */
public interface TimerKind {

    String name();
}
