package com.registration.common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientIdTest {

    @Test
    void roundTripsThroughRawValue() {
        ClientId id = ClientId.parse("123456789012");

        assertEquals(123456789012L, id.rawValue());
        assertEquals("123456789012", id.toString());
    }

    @Test
    void preservesLeadingZerosThroughRawValue() {
        ClientId id = ClientId.parse("000000000042");

        assertEquals(42L, id.rawValue());
        assertEquals("000000000042", ClientId.ofRawValue(id.rawValue()).toString());
    }

    @Test
    void rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> ClientId.parse("123"));
    }

    @Test
    void rejectsNonDigits() {
        assertThrows(IllegalArgumentException.class, () -> ClientId.parse("12345678901a"));
    }

    @Test
    void rejectsRawValueOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> ClientId.ofRawValue(-1L));
        assertThrows(IllegalArgumentException.class, () -> ClientId.ofRawValue(1_000_000_000_000L));
    }

    @Test
    void equalityIsByValue() {
        assertEquals(ClientId.parse("000000000042"), ClientId.ofRawValue(42L));
    }

    @Test
    void randomProducesAValidTwelveDigitId() {
        for (int i = 0; i < 1000; i++) {
            ClientId id = ClientId.random();
            assertEquals(id, ClientId.parse(id.toString()));
        }
    }
}
