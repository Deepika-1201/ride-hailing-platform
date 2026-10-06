package com.ridehailing.operations.app;

import com.ridehailing.dispatch.AvailabilityStatus;
import com.ridehailing.dispatch.DispatchApi;
import com.ridehailing.driver.DriverApi;
import com.ridehailing.platform.InvariantCheck;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** I8: no suspended driver is {@code AVAILABLE} or {@code OFFERED} (LLD §17.3), across driver and dispatch. */
@Component
class SuspendedDriversCheck implements InvariantCheck {

    private static final Set<AvailabilityStatus> MATCHABLE = Set.of(AvailabilityStatus.AVAILABLE,
            AvailabilityStatus.OFFERED);

    private final DriverApi drivers;
    private final DispatchApi dispatch;

    SuspendedDriversCheck(DriverApi drivers, DispatchApi dispatch) {
        this.drivers = drivers;
        this.dispatch = dispatch;
    }

    @Override
    public String id() {
        return "I8";
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<String> violations(String cityId) {
        List<UUID> suspended = drivers.suspended(cityId);
        return suspended.stream().map(dispatch::status).filter(status -> MATCHABLE.contains(status.status()))
                .map(status -> "driver " + status.driverId() + " is suspended but " + status.status()).toList();
    }
}
