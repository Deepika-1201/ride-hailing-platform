package com.ridehailing.dispatch.app;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The rankers by name; unimplemented strategies fall back to the nearest-driver policy. */
@Component
class Rankers {

    private static final Logger log = LoggerFactory.getLogger(Rankers.class);

    private final Map<String, CandidateRanker> byName;

    Rankers(List<CandidateRanker> rankers) {
        this.byName = rankers.stream().collect(Collectors.toMap(CandidateRanker::name, Function.identity()));
    }

    CandidateRanker named(String name) {
        CandidateRanker ranker = byName.get(name);
        if (ranker != null) {
            return ranker;
        }
        log.warn("No ranker named {}; using {}", name, NearestDriverRanker.NAME);
        return byName.get(NearestDriverRanker.NAME);
    }
}
