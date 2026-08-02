package com.registration.common.crypto;

import com.registration.common.protocol.Challenge;
import com.registration.common.protocol.ChallengeResponse;
import com.registration.common.protocol.Nonce;
import com.registration.common.protocol.NonceSignature;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Signs and verifies a {@link Challenge} with the Shared Signing Key (ADR-0009). The
 * private key never needs the Server to hold it and vice versa for the public key —
 * that asymmetry is the whole point of choosing Ed25519 over an HMAC shared secret.
 */
public final class Ed25519 {

    private static final String ALGORITHM = "Ed25519";
    public static final int SEED_LENGTH = 32;
    public static final int PUBLIC_KEY_LENGTH = 32;

    // Fixed 12-byte X.509 SubjectPublicKeyInfo prefix for a raw 32-byte Ed25519 public key
    // (RFC 8410): lets KeyFactory parse a bare key without hand-decoding its EdECPoint.
    private static final byte[] X509_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private Ed25519() {
    }

    public static PrivateKey parsePrivateKey(String base64Seed) {
        byte[] seed = Base64.getDecoder().decode(base64Seed);
        if (seed.length != SEED_LENGTH) {
            throw new IllegalArgumentException("Ed25519 seed must be " + SEED_LENGTH + " bytes, got: " + seed.length);
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            return keyFactory.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to parse Ed25519 private key", e);
        }
    }

    public static PublicKey parsePublicKey(String base64Key) {
        byte[] rawKey = Base64.getDecoder().decode(base64Key);
        if (rawKey.length != PUBLIC_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Ed25519 public key must be " + PUBLIC_KEY_LENGTH + " bytes, got: " + rawKey.length);
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            byte[] encoded = new byte[X509_PREFIX.length + rawKey.length];
            System.arraycopy(X509_PREFIX, 0, encoded, 0, X509_PREFIX.length);
            System.arraycopy(rawKey, 0, encoded, X509_PREFIX.length, rawKey.length);
            return keyFactory.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to parse Ed25519 public key", e);
        }
    }

    public static ChallengeResponse sign(PrivateKey privateKey, Challenge challenge) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(challenge.value());
            return ChallengeResponse.of(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign Challenge", e);
        }
    }

    public static boolean verify(PublicKey publicKey, Challenge challenge, ChallengeResponse response) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(challenge.value());
            return verifier.verify(response.value());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public static NonceSignature sign(PrivateKey privateKey, Nonce nonce) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(nonce.value());
            return NonceSignature.of(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign Nonce", e);
        }
    }

    public static boolean verify(PublicKey publicKey, Nonce nonce, NonceSignature signature) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(nonce.value());
            return verifier.verify(signature.value());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
