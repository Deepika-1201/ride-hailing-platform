package com.ridehailing.architecture.fixture;

import org.springframework.modulith.Modulithic;

/** Root of a two-module fixture with a deliberate boundary violation; never part of the application. */
@Modulithic
public final class FixtureApplication {

    private FixtureApplication() {
    }
}
