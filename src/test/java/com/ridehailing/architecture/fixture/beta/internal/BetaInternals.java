package com.ridehailing.architecture.fixture.beta.internal;

import com.ridehailing.architecture.fixture.beta.BetaApi;

public class BetaInternals implements BetaApi {

    @Override
    public int value() {
        return 42;
    }
}
