package com.ridehailing.payment.app;

/**
 * A provider's answer about a charge or refund. {@code PENDING}: accepted, decided later (UPI collect requests);
 * {@code NOT_FOUND}: a status check for a key the provider never received.
 */
record ProviderAnswer(Status status, String reference, String failureCode) {

    enum Status {
        SUCCEEDED,
        FAILED,
        PENDING,
        NOT_FOUND
    }

    static ProviderAnswer succeeded(String reference) {
        return new ProviderAnswer(Status.SUCCEEDED, reference, null);
    }

    static ProviderAnswer failed(String failureCode) {
        return new ProviderAnswer(Status.FAILED, null, failureCode);
    }

    static ProviderAnswer pending() {
        return new ProviderAnswer(Status.PENDING, null, null);
    }

    static ProviderAnswer notFound() {
        return new ProviderAnswer(Status.NOT_FOUND, null, null);
    }

    boolean isFinal() {
        return status == Status.SUCCEEDED || status == Status.FAILED;
    }
}
