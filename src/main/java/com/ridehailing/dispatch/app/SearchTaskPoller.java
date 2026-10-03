package com.ridehailing.dispatch.app;

import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Claims due search tasks on every dispatch node (LLD §8.3). */
@Component
class SearchTaskPoller implements Poller {

    static final String NAME = "search-task-poller";

    private final SearchAttempts attempts;
    private final DispatchProperties properties;

    SearchTaskPoller(SearchAttempts attempts, DispatchProperties properties) {
        this.attempts = attempts;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Role role() {
        return Role.DISPATCH;
    }

    @Override
    public int threads() {
        return properties.workers();
    }

    @Override
    public Duration interval() {
        return properties.pollInterval();
    }

    @Override
    public boolean poll() {
        return attempts.runNext();
    }
}
