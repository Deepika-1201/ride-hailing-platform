package com.ridehailing.architecture.fixture.alpha;

import com.ridehailing.architecture.fixture.beta.internal.BetaInternals;

public class AlphaService {

    public int answer() {
        return new BetaInternals().value();
    }
}
