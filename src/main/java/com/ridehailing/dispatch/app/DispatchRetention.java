package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.db.DecisionRepository;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** Decision records are kept 30 days (LLD §5.7), deleted in batches until a batch comes back short. */
@Service
public class DispatchRetention {

    static final Duration KEEP_DECISIONS = Duration.ofDays(30);
    static final int BATCH = 10_000;

    private final DecisionRepository decisions;

    DispatchRetention(DecisionRepository decisions) {
        this.decisions = decisions;
    }

    /** Answers how many decisions it deleted. */
    public int purge() {
        int deleted = 0;
        int batch;
        do {
            batch = decisions.deleteOlderThan(KEEP_DECISIONS, BATCH);
            deleted += batch;
        } while (batch == BATCH);
        return deleted;
    }
}
