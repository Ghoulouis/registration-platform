package com.registration.common.protocol;

import java.util.Arrays;

/**
 * Outcome of a REGISTER or RENEW call. REGISTER and RENEW are kept strictly
 * symmetric (ADR-0003): REGISTER only succeeds without a live Registration,
 * RENEW only succeeds with one.
 */
public enum StatusCode {

    SUCCESS((byte) 0x00),
    ALREADY_REGISTERED((byte) 0x01),
    NOT_REGISTERED((byte) 0x02);

    private final byte code;

    StatusCode(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static StatusCode fromCode(byte code) {
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown status code: " + code));
    }
}
