package com.ridehailing.rider.app;

import com.ridehailing.rider.RiderApi;
import com.ridehailing.rider.db.PaymentMethodRepository;
import com.ridehailing.rider.db.RiderRepository.RiderRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class RiderService implements RiderApi {

    private final RiderProfiles profiles;
    private final PaymentMethodRepository methods;

    RiderService(RiderProfiles profiles, PaymentMethodRepository methods) {
        this.profiles = profiles;
        this.methods = methods;
    }

    @Override
    public Optional<PaymentMethodRef> paymentMethod(UUID riderId, UUID methodId) {
        RiderRow rider = profiles.profile(riderId);
        return methods.activeOf(riderId, methodId != null ? methodId : rider.defaultPaymentMethodId())
                .map(method -> new PaymentMethodRef(method.id(), method.type()));
    }

    @Override
    public RiderSnapshot snapshot(UUID riderId) {
        return new RiderSnapshot(riderId, profiles.profile(riderId).firstName());
    }
}
