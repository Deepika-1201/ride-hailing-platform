package com.ridehailing.realtime.ws;

import com.ridehailing.location.LiveIndex;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The messages this module writes itself, as {@code server-messages.v1.json} has them; pushes arrive as JSON. */
final class Messages {

    private Messages() {
    }

    record ErrorMessage(String type, String code, String message) {

        static ErrorMessage of(String code, String message) {
            return new ErrorMessage("error", code, message);
        }
    }

    record Reconnect(String type, long afterMs) {

        Reconnect(long afterMs) {
            this("reconnect", afterMs);
        }
    }

    record OpsSnapshot(String type, String cityId, Instant at, boolean truncated, List<OpsDriver> drivers) {

        OpsSnapshot(String cityId, Instant at, boolean truncated, List<OpsDriver> drivers) {
            this("ops_snapshot", cityId, at, truncated, drivers);
        }
    }

    /** {@code category} and {@code rideId} may be null. */
    record OpsDriver(UUID driverId, double lat, double lon, LiveIndex.Status status, String category, UUID rideId,
            boolean stale) {
    }

    /** {@code headingDeg} and {@code etaS} may be null. */
    record DriverPosition(String type, UUID rideId, double lat, double lon, Double headingDeg, long seq, Integer etaS) {

        DriverPosition(UUID rideId, double lat, double lon, Double headingDeg, long seq, Integer etaS) {
            this("driver_position", rideId, lat, lon, headingDeg, seq, etaS);
        }
    }
}
