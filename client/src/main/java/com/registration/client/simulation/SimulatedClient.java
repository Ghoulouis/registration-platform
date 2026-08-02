package com.registration.client.simulation;

import com.registration.client.retry.CallFailedException;
import com.registration.client.retry.RetryingRequester;
import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.Nonce;
import com.registration.common.protocol.NonceSignature;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PrivateKey;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One Simulated Client's full lifecycle (CONTEXT.md): REGISTER, then RENEW on a
 * randomized schedule (ADR-0006's Renewal Window) until interrupted, then a
 * best-effort voluntary CANCEL (ADR-0004). Runs entirely on the virtual thread that
 * invokes {@link #run()} — no shared mutable state with other Simulated Clients.
 */
public final class SimulatedClient implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SimulatedClient.class);

    private final ClientId clientId;
    private final RetryingRequester requester;
    private final PrivateKey signingKey;
    private final int assumedValidityPeriodSeconds;
    private final int renewalWindowMinPercent;
    private final int renewalWindowMaxPercent;

    // Set from Register's response, updated after every successful Renewal (ADR-0010).
    // Touched only by this Simulated Client's own thread - no synchronization needed.
    private Nonce currentNonce;

    public SimulatedClient(
            ClientId clientId,
            RetryingRequester requester,
            PrivateKey signingKey,
            int assumedValidityPeriodSeconds,
            int renewalWindowMinPercent,
            int renewalWindowMaxPercent) {
        this.clientId = clientId;
        this.requester = requester;
        this.signingKey = signingKey;
        this.assumedValidityPeriodSeconds = assumedValidityPeriodSeconds;
        this.renewalWindowMinPercent = renewalWindowMinPercent;
        this.renewalWindowMaxPercent = renewalWindowMaxPercent;
    }

    @Override
    public void run() {
        int validityPeriodSeconds;
        try {
            validityPeriodSeconds = register();
        } catch (CallFailedException e) {
            log.debug("[{}] REGISTER failed, giving up: {}", clientId, e.getMessage());
            return;
        } catch (InterruptedException e) {
            return; // shut down before ever registering; nothing to cancel
        }

        try {
            while (true) {
                Thread.sleep(renewalDelayMillis(validityPeriodSeconds));
                validityPeriodSeconds = renew();
            }
        } catch (InterruptedException e) {
            // shutdown signal: fall through to voluntary Cancellation
        } catch (RenewalFailedException e) {
            log.debug("[{}] RENEW failed, giving up: {}", clientId, e.getMessage());
            return; // not confident we're still registered; don't attempt to cancel
        }

        cancel();
    }

    private int register() throws InterruptedException {
        RegisterResponse response = requester.register(clientId, signingKey);
        if (response.status() == StatusCode.SUCCESS) {
            currentNonce = response.nonce();
            log.debug("[{}] REGISTER succeeded, validity period {}s", clientId, response.validityPeriodSeconds());
            return response.validityPeriodSeconds();
        }
        if (response.status() == StatusCode.ALREADY_REGISTERED) {
            // Almost certainly our own earlier attempt landing (ADR-0005) — Client IDs are
            // independently random, so we proceed as registered. The Server doesn't return a
            // real period on this status, so fall back to our own assumed value, same as we
            // would before any authoritative response (grilled Question 5). It does return the
            // current Nonce though (ADR-0010) — without that we'd have no way to ever Renew.
            currentNonce = response.nonce();
            log.info("[{}] REGISTER returned ALREADY_REGISTERED, proceeding as registered "
                    + "with assumed validity period {}s", clientId, assumedValidityPeriodSeconds);
            return assumedValidityPeriodSeconds;
        }
        // CHALLENGE_REJECTED: genuinely failed to authenticate (expired or already-consumed
        // pending Nonce) — unlike ALREADY_REGISTERED, this isn't our own earlier attempt landing.
        throw new CallFailedException("REGISTER challenge rejected");
    }

    private int renew() throws InterruptedException {
        NonceSignature signature = Ed25519.sign(signingKey, currentNonce);
        RenewResponse response;
        try {
            response = requester.renew(clientId, signature);
        } catch (CallFailedException e) {
            throw new RenewalFailedException(e.getMessage());
        }
        if (response.status() != StatusCode.SUCCESS) {
            throw new RenewalFailedException("Server returned " + response.status());
        }
        currentNonce = response.nonce();
        log.debug("[{}] RENEW succeeded, validity period {}s", clientId, response.validityPeriodSeconds());
        return response.validityPeriodSeconds();
    }

    private void cancel() {
        try {
            NonceSignature signature = Ed25519.sign(signingKey, currentNonce);
            CancelResponse response = requester.cancel(clientId, signature);
            log.info("[{}] CANCEL returned {}", clientId, response.status());
        } catch (CallFailedException e) {
            log.debug("[{}] CANCEL failed (best-effort during shutdown): {}", clientId, e.getMessage());
        } catch (InterruptedException e) {
            // already shutting down; nothing more to do
        }
    }

    private long renewalDelayMillis(int validityPeriodSeconds) {
        double minFraction = renewalWindowMinPercent / 100.0;
        double maxFraction = renewalWindowMaxPercent / 100.0;
        double fraction = ThreadLocalRandom.current().nextDouble(minFraction, maxFraction);
        return (long) (validityPeriodSeconds * 1000L * fraction);
    }

    private static final class RenewalFailedException extends RuntimeException {
        RenewalFailedException(String message) {
            super(message);
        }
    }
}
