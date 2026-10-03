package com.ridehailing.dispatch.jobs;

import com.ridehailing.dispatch.app.LiveIndexReconciler;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class LiveIndexReconcilerJob implements RecurringJob {

    private final LiveIndexReconciler reconciler;

    LiveIndexReconcilerJob(LiveIndexReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public String name() {
        return "live-index-reconciler";
    }

    @Override
    public Role role() {
        return Role.DISPATCH;
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(30);
    }

    @Override
    public void run() {
        reconciler.reconcileAll();
    }
}
