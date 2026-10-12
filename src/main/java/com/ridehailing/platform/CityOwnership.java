package com.ridehailing.platform;

import java.util.List;
import java.util.Set;

public interface CityOwnership {

    Set<String> configuredCities();

    /** Cached claims for preparing work; each must be checked again when committing it. */
    List<Claim> owned();

    /** Commits database-only work under the captured claim, outside any existing transaction. */
    boolean runIfOwned(Claim claim, Runnable databaseWork);

    record Claim(String cityId, String holder, long token) {
        public Claim {
            if (cityId == null || cityId.isBlank() || holder == null || holder.isBlank() || token < 1) {
                throw new IllegalArgumentException("A city claim requires its city, holder and positive fencing token");
            }
        }
    }
}