package com.ridehailing.platform.push;

import com.ridehailing.platform.PushBus;
import com.ridehailing.platform.PushBusContract;

class InMemoryPushBusTests extends PushBusContract {

    @Override
    protected PushBus newBus() {
        return new InMemoryPushBus(JSON);
    }
}
