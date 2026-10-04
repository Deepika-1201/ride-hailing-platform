package com.ridehailing.payment.app;

import com.ridehailing.payment.db.ChargeRepository;
import com.ridehailing.platform.InvariantCheck;
import java.util.List;
import org.springframework.stereotype.Component;

/** I7: one charge per ride and purpose; refunds never exceed their charge; no double charge (LLD §17.3, §11.10). */
@Component
class ChargesCheck implements InvariantCheck {

    private final ChargeRepository charges;

    ChargesCheck(ChargeRepository charges) {
        this.charges = charges;
    }

    @Override
    public String id() {
        return "I7";
    }

    @Override
    public List<String> violations(String cityId) {
        return charges.violations(cityId);
    }
}
