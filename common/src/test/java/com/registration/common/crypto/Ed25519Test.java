package com.registration.common.crypto;

import com.registration.common.protocol.Challenge;
import com.registration.common.protocol.ChallengeResponse;
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
        Challenge challenge = Challenge.random();

        ChallengeResponse response = Ed25519.sign(privateKey, challenge);

        assertTrue(Ed25519.verify(publicKey, challenge, response));
    }

    @Test
    void rejectsASignatureOverADifferentChallenge() {
        PrivateKey privateKey = Ed25519.parsePrivateKey(PRIVATE_SEED_B64);
        PublicKey publicKey = Ed25519.parsePublicKey(PUBLIC_KEY_B64);
        ChallengeResponse response = Ed25519.sign(privateKey, Challenge.random());

        assertFalse(Ed25519.verify(publicKey, Challenge.random(), response));
    }
}
