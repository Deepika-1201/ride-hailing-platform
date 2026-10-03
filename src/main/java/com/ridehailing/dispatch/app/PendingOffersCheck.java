package com.ridehailing.dispatch.app;

import com.ridehailing.dispatch.db.OfferRepository;
import com.ridehailing.platform.InvariantCheck;
import java.util.List;
import org.springframework.stereotype.Component;

/** I2: no driver and no ride with two pending offers; no ride offered twice to one driver (LLD §17.3). */
@Component
class PendingOffersCheck implements InvariantCheck {

    private final OfferRepository offers;

    PendingOffersCheck(OfferRepository offers) {
        this.offers = offers;
    }

    @Override
    public String id() {
        return "I2";
    }

    @Override
    public List<String> violations(String cityId) {
        return offers.duplicatedOffers(cityId);
    }
}
