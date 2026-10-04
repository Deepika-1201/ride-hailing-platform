package com.ridehailing.rider.app;

import com.ridehailing.rider.RiderApi;
import com.ridehailing.rider.db.PaymentMethodRepository;
import com.ridehailing.rider.db.PaymentMethodRepository.MethodRow;
import com.ridehailing.rider.db.RiderRepository.RiderRow;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
class RiderService implements RiderApi {

    private static final String CASH = "CASH";

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
                .map(RiderService::ref);
    }

    @Override
    public Optional<PaymentMethodRef> onlineMethod(UUID riderId, UUID preferredId) {
        RiderRow rider = profiles.profile(riderId);
        return Stream.of(preferredId, rider.defaultPaymentMethodId())
                .filter(Objects::nonNull)
                .flatMap(id -> methods.activeOf(riderId, id).stream())
                .filter(method -> !CASH.equals(method.type()))
                .findFirst()
                .or(() -> methods.newestOnline(riderId))
                .map(RiderService::ref);
    }

    @Override
    public RiderSnapshot snapshot(UUID riderId) {
        return new RiderSnapshot(riderId, profiles.profile(riderId).firstName());
    }

    private static PaymentMethodRef ref(MethodRow method) {
        return new PaymentMethodRef(method.id(), method.type(), method.providerRef());
    }
}
