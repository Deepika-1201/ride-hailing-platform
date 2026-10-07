package com.ridehailing.dispatch.jobs;

import com.ridehailing.dispatch.app.LiveIndexReconciler;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Every 2 s, so a city whose live index lost its data is repaired within NFR-7's 10 s (LLD §8.10). */
@Component
class LiveIndexWatchJob implements RecurringJob {

    private final LiveIndexReconciler reconciler;

    LiveIndexWatchJob(LiveIndexReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public String name() {
        return "live-index-watch";
    }

    @Override
    public Role role() {
        return Role.DISPATCH;
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(2);
    }

    @Override
    public void run() {
        reconciler.watch();
    }
}
