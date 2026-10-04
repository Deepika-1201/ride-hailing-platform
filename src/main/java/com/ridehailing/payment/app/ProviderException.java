package com.ridehailing.payment.app;

/** A provider call without an answer: a timeout, a connection error or a 5xx. The outcome is unknown (LLD §11.2). */
class ProviderException extends RuntimeException {

    ProviderException(String message) {
        super(message);
    }
}
