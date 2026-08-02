package com.registration.common.crypto;

import com.registration.common.protocol.Nonce;
import com.registration.common.protocol.NonceSignature;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ed25519Test {

    // Demo keypair for tests and default config values (ADR-0009) — not for production use.
    private static final String PRIVATE_SEED_B64 = "pU55QBNBWdgYnCyCaZsfU3jImcyqZKGmSv3Nb+YEEbM=";
    private static final String PUBLIC_KEY_B64 = "OyqZa3x46M9IqazQAsypDYZr244z47nMSQVPmoK7Kcw=";

    @Test
    void verifiesASignatureProducedByTheMatchingPrivateKey() {
        PrivateKey privateKey = Ed25519.parsePrivateKey(PRIVATE_SEED_B64);
        PublicKey publicKey = Ed25519.parsePublicKey(PUBLIC_KEY_B64);
        Nonce nonce = Nonce.random();

        NonceSignature signature = Ed25519.sign(privateKey, nonce);

        assertTrue(Ed25519.verify(publicKey, nonce, signature));
    }

    @Test
    void rejectsASignatureOverADifferentNonce() {
        PrivateKey privateKey = Ed25519.parsePrivateKey(PRIVATE_SEED_B64);
        PublicKey publicKey = Ed25519.parsePublicKey(PUBLIC_KEY_B64);
        NonceSignature signature = Ed25519.sign(privateKey, Nonce.random());

        assertFalse(Ed25519.verify(publicKey, Nonce.random(), signature));
    }
}
