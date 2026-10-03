package com.ridehailing.shared;

/** An amount in paise, as JSON {@code {"amount_paise": …, "currency": "INR"}}. */
public record Money(long amountPaise, String currency) {
}
