package com.registration.client.retry;

/** All retry attempts for one logical call were exhausted without success. */
public final class CallFailedException extends RuntimeException {

    public CallFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    /** No {@link Throwable} cause: used when the call got a well-formed but unusable response. */
    public CallFailedException(String message) {
        super(message);
    }
}
