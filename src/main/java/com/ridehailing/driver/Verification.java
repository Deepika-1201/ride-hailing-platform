package com.ridehailing.driver;

/** Where a driver is in onboarding; only {@code VERIFIED} drivers may go online. */
public enum Verification {
    PENDING,
    VERIFIED,
    REJECTED
}
