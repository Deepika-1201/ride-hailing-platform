package com.ridehailing.platform;

import java.util.List;

/** A rule across one module's rows, such as "no driver holds two pending offers" (LLD §17.3). */
public interface InvariantCheck {

    /** Its number in LLD §17.3, such as {@code I2}. */
    String id();

    /** A description of each violation in the city, or in every city when {@code cityId} is null. */
    List<String> violations(String cityId);
}
