package com.registration.server.domain;

import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.store.RegistrationStore;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Applies REGISTER/RENEW/CANCEL symmetry (ADR-0003, ADR-0004), same as the Distributed
 * Server's equivalent — synchronous here since {@link RegistrationStore} has no I/O to await.
 */
@Service
public class RegistrationService {

    private final RegistrationStore store;
    private final Duration validityPeriod;

    public RegistrationService(RegistrationStore store, RegistrationProperties properties) {
        this.store = store;
        this.validityPeriod = Duration.ofSeconds(properties.validityPeriodSeconds());
    }

    public ProtocolMessage handle(ProtocolMessage request) {
        return switch (request) {
            case RegisterRequest r -> register(r);
            case RenewRequest r -> renew(r);
            case CancelRequest r -> cancel(r);
            case RegisterResponse ignored -> throw unexpected(request);
            case RenewResponse ignored -> throw unexpected(request);
            case CancelResponse ignored -> throw unexpected(request);
        };
    }

    private ProtocolMessage register(RegisterRequest request) {
        boolean created = store.tryRegister(request.clientId(), validityPeriod);
        return created
                ? new RegisterResponse(StatusCode.SUCCESS, (int) validityPeriod.toSeconds())
                : new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0);
    }

    private ProtocolMessage renew(RenewRequest request) {
        boolean renewed = store.renew(request.clientId(), validityPeriod);
        return renewed
                ? new RenewResponse(StatusCode.SUCCESS, (int) validityPeriod.toSeconds())
                : new RenewResponse(StatusCode.NOT_REGISTERED, 0);
    }

    private ProtocolMessage cancel(CancelRequest request) {
        boolean cancelled = store.cancel(request.clientId());
        return cancelled
                ? new CancelResponse(StatusCode.SUCCESS)
                : new CancelResponse(StatusCode.NOT_REGISTERED);
    }

    private static IllegalArgumentException unexpected(ProtocolMessage request) {
        return new IllegalArgumentException("Server does not accept " + request.type());
    }
}
