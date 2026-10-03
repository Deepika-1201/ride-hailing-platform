package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.db.AvailabilityRepository;
import com.ridehailing.platform.InvariantCheck;
import java.util.List;
import org.springframework.stereotype.Component;

/** I3: a driver is {@code OFFERED} exactly when a {@code PENDING} offer is theirs (LLD §17.3). */
@Component
class OfferedDriversCheck implements InvariantCheck {

    private final AvailabilityRepository availability;

    OfferedDriversCheck(AvailabilityRepository availability) {
        this.availability = availability;
    }

    @Override
    public String id() {
        return "I3";
    }

    @Override
    public List<String> violations(String cityId) {
        return availability.offeredWithoutTheirOffer(cityId);
    }
}
