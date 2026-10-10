package com.ridehailing.ride.app;

import com.ridehailing.platform.ApiException;
import com.ridehailing.ride.db.RideRepository.RideRow;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** The ride commands' errors (LLD §13.2). */
public final class RideErrors {

    private RideErrors() {
    }

    static ApiException invalidTransition(RideRow ride) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                "The ride is " + ride.status() + ", which doesn't allow this.", null,
                Map.of("current_status", ride.status().name(), "current_version", ride.version()));
    }

    public static ApiException reassigned() {
        return new ApiException(HttpStatus.CONFLICT, "RIDE_REASSIGNED",
                "This ride was given to another driver; refresh your rides.");
    }
}
