package com.registration.common.protocol;

import java.util.Arrays;

/** The 1-byte message kind carried in every frame's header (see ADR-0003, ADR-0004). */
public enum MessageType {

    REGISTER_REQUEST((byte) 0x01),
    REGISTER_RESPONSE((byte) 0x02),
    RENEW_REQUEST((byte) 0x03),
    RENEW_RESPONSE((byte) 0x04),
    CANCEL_REQUEST((byte) 0x05),
    CANCEL_RESPONSE((byte) 0x06);

    private final byte code;

    MessageType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static MessageType fromCode(byte code) {
        return Arrays.stream(values())
                .filter(type -> type.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown message type code: " + code));
    }
}
