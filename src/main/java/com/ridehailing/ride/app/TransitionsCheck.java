package com.ridehailing.ride.app;

import com.ridehailing.platform.InvariantCheck;
import com.ridehailing.ride.db.TransitionRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** I5: every transition row follows the transition table, in order, and the ride agrees with its log (LLD §17.3). */
@Component
class TransitionsCheck implements InvariantCheck {

    private final TransitionRepository transitions;

    TransitionsCheck(TransitionRepository transitions) {
        this.transitions = transitions;
    }

    @Override
    public String id() {
        return "I5";
    }

    @Override
    public List<String> violations(String cityId) {
        return transitions.outsideTheTable(cityId, RideTransitions.allowed());
    }
}
