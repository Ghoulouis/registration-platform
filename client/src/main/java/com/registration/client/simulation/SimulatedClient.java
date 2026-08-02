package com.registration.client.simulation;

import com.registration.client.retry.CallFailedException;
import com.registration.client.retry.RetryingRequester;
import com.registration.client.stats.OperationType;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final int assumedValidityPeriodSeconds;
    private final int renewalWindowMinPercent;
    private final int renewalWindowMaxPercent;

    public SimulatedClient(
            ClientId clientId,
            RetryingRequester requester,
            int assumedValidityPeriodSeconds,
            int renewalWindowMinPercent,
            int renewalWindowMaxPercent) {
        this.clientId = clientId;
        this.requester = requester;
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
                validityPeriodSeconds = renewOrReregister();
            }
        } catch (InterruptedException e) {
            // shutdown signal: fall through to voluntary Cancellation
        } catch (CallFailedException e) {
            log.debug("[{}] Giving up: {}", clientId, e.getMessage());
            return; // not confident we're still registered; don't attempt to cancel
        }

        cancel();
    }

    private int register() throws InterruptedException {
        RegisterResponse response =
                (RegisterResponse) requester.send(OperationType.REGISTER, new RegisterRequest(clientId));
        if (response.status() == StatusCode.SUCCESS) {
            log.debug("[{}] REGISTER succeeded, validity period {}s", clientId, response.validityPeriodSeconds());
            return response.validityPeriodSeconds();
        }
        // ALREADY_REGISTERED: almost certainly our own earlier attempt landing (ADR-0005) —
        // Client IDs are independently random, so we proceed as registered. The Server doesn't
        // return a real period on this status, so fall back to our own assumed value, same as
        // we would before any authoritative response (grilled Question 5).
        log.info("[{}] REGISTER returned ALREADY_REGISTERED, proceeding as registered "
                + "with assumed validity period {}s", clientId, assumedValidityPeriodSeconds);
        return assumedValidityPeriodSeconds;
    }

    /** RENEW; if the Registration was lost (NOT_REGISTERED), transparently REGISTER again. */
    private int renewOrReregister() throws InterruptedException {
        RenewResponse response = (RenewResponse) requester.send(OperationType.RENEW, new RenewRequest(clientId));
        if (response.status() == StatusCode.SUCCESS) {
            log.debug("[{}] RENEW succeeded, validity period {}s", clientId, response.validityPeriodSeconds());
            return response.validityPeriodSeconds();
        }
        // NOT_REGISTERED: our Registration expired or was otherwise lost. Re-register rather
        // than giving up, so a long-running Simulated Client recovers instead of dying outright.
        log.debug("[{}] RENEW returned NOT_REGISTERED, registering again", clientId);
        return register();
    }

    private void cancel() {
        try {
            CancelResponse response =
                    (CancelResponse) requester.send(OperationType.CANCEL, new CancelRequest(clientId));
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
}
