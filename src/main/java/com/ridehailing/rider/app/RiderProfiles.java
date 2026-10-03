package com.ridehailing.rider.app;

import com.ridehailing.platform.ApiException;
import com.ridehailing.platform.Transactions;
import com.ridehailing.rider.db.PaymentMethodRepository;
import com.ridehailing.rider.db.PaymentMethodRepository.MethodRow;
import com.ridehailing.rider.db.RiderRepository;
import com.ridehailing.rider.db.RiderRepository.RiderRow;
import com.ridehailing.rider.db.SavedPlaceRepository;
import com.ridehailing.rider.db.SavedPlaceRepository.SavedPlace;
import com.ridehailing.shared.GeoPoint;
import com.ridehailing.shared.Ids;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The signed-in rider's profile, places and payment methods. The rider row and its cash method are created on the
 * first call, since identity doesn't depend on rider (LLD §13.3).
 */
@Service
public class RiderProfiles {

    static final int PLACES_LIMIT = 10;
    private static final String CASH = "CASH";

    private final RiderRepository riders;
    private final SavedPlaceRepository places;
    private final PaymentMethodRepository methods;
    private final Transactions transactions;

    RiderProfiles(RiderRepository riders, SavedPlaceRepository places, PaymentMethodRepository methods,
            Transactions transactions) {
        this.riders = riders;
        this.places = places;
        this.methods = methods;
        this.transactions = transactions;
    }

    public RiderRow profile(UUID riderId) {
        return ensure(riderId);
    }

    /** Null fields stay as they are. */
    public RiderRow update(UUID riderId, String firstName, String lastName, String email) {
        ensure(riderId);
        return transactions.execute(() -> riders.update(riderId, firstName, lastName, email));
    }

    public List<SavedPlace> places(UUID riderId) {
        ensure(riderId);
        return places.list(riderId);
    }

    /** Counted under the rider row's lock, so concurrent saves can't pass the limit together. */
    public SavedPlace addPlace(UUID riderId, String label, String name, GeoPoint location) {
        ensure(riderId);
        return transactions.execute(() -> {
            riders.lock(riderId);
            if (places.count(riderId) >= PLACES_LIMIT) {
                throw new ApiException(HttpStatus.CONFLICT, "PLACES_LIMIT_REACHED",
                        "You can save up to " + PLACES_LIMIT + " places; delete one first.");
            }
            UUID id = Ids.newId();
            if (!places.insert(id, riderId, label, name, location)) {
                throw ApiException.alreadyExists("You have a place with this label already.");
            }
            return places.get(id);
        });
    }

    public void deletePlace(UUID riderId, UUID placeId) {
        if (!places.delete(riderId, placeId)) {
            throw ApiException.notFound();
        }
    }

    public List<PaymentMethod> paymentMethods(UUID riderId) {
        UUID defaultId = ensure(riderId).defaultPaymentMethodId();
        return methods.active(riderId).stream().map(method -> PaymentMethod.of(method, defaultId)).toList();
    }

    /** A mock provider token, never a card number; the method isn't made the default. */
    public PaymentMethod addPaymentMethod(UUID riderId, String type, String providerToken, String display) {
        UUID defaultId = ensure(riderId).defaultPaymentMethodId();
        UUID id = Ids.newId();
        return transactions.execute(() -> {
            methods.insert(id, riderId, type, providerToken, display);
            return PaymentMethod.of(methods.activeOf(riderId, id).orElseThrow(), defaultId);
        });
    }

    /** Cash can't be removed; removing the default makes cash the default. */
    public void removePaymentMethod(UUID riderId, UUID methodId) {
        ensure(riderId);
        transactions.run(() -> {
            RiderRow rider = riders.lock(riderId);
            MethodRow method = methods.activeOf(riderId, methodId).orElseThrow(ApiException::notFound);
            if (method.type().equals(CASH)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_METHOD_INVALID",
                        "Cash can't be removed.");
            }
            methods.deactivate(methodId);
            if (methodId.equals(rider.defaultPaymentMethodId())) {
                riders.setDefaultMethod(riderId, methods.cashOf(riderId));
            }
        });
    }

    public PaymentMethod setDefaultPaymentMethod(UUID riderId, UUID methodId) {
        ensure(riderId);
        return transactions.execute(() -> {
            riders.lock(riderId);
            MethodRow method = methods.activeOf(riderId, methodId).orElseThrow(ApiException::notFound);
            riders.setDefaultMethod(riderId, methodId);
            return PaymentMethod.of(method, methodId);
        });
    }

    private RiderRow ensure(UUID riderId) {
        return riders.find(riderId).orElseGet(() -> transactions.execute(() -> {
            if (riders.insertIfAbsent(riderId)) {
                UUID cash = Ids.newId();
                methods.insertCash(cash, riderId);
                riders.setDefaultMethod(riderId, cash);
            }
            return riders.find(riderId).orElseThrow();
        }));
    }

    public record PaymentMethod(UUID id, String type, String display, boolean isDefault, Instant createdAt) {

        static PaymentMethod of(MethodRow method, UUID defaultId) {
            return new PaymentMethod(method.id(), method.type(), method.display(), method.id().equals(defaultId),
                    method.createdAt());
        }
    }
}
