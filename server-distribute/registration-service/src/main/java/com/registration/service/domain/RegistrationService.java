package com.registration.service.domain;

import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.service.config.RegistrationProperties;
import com.registration.service.store.RegistrationStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Applies REGISTER/RENEW/CANCEL symmetry (ADR-0003, ADR-0004): REGISTER only succeeds
 * without a live Registration; RENEW and CANCEL only succeed with one.
 */
@Service
public class RegistrationService {

    private final RegistrationStore store;
    private final Duration validityPeriod;

    public RegistrationService(RegistrationStore store, RegistrationProperties properties) {
        this.store = store;
        this.validityPeriod = Duration.ofSeconds(properties.validityPeriodSeconds());
    }

    public Mono<ProtocolMessage> handle(ProtocolMessage request) {
        return switch (request) {
            case RegisterRequest r -> register(r);
            case RenewRequest r -> renew(r);
            case CancelRequest r -> cancel(r);
            case RegisterResponse ignored -> Mono.error(unexpected(request));
            case RenewResponse ignored -> Mono.error(unexpected(request));
            case CancelResponse ignored -> Mono.error(unexpected(request));
        };
    }

    private Mono<ProtocolMessage> register(RegisterRequest request) {
        return store.tryRegister(request.clientId(), validityPeriod)
                .map(created -> created
                        ? new RegisterResponse(StatusCode.SUCCESS, (int) validityPeriod.toSeconds())
                        : new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0));
    }

    private Mono<ProtocolMessage> renew(RenewRequest request) {
        return store.renew(request.clientId(), validityPeriod)
                .map(renewed -> renewed
                        ? new RenewResponse(StatusCode.SUCCESS, (int) validityPeriod.toSeconds())
                        : new RenewResponse(StatusCode.NOT_REGISTERED, 0));
    }

    private Mono<ProtocolMessage> cancel(CancelRequest request) {
        return store.cancel(request.clientId())
                .map(cancelled -> cancelled
                        ? new CancelResponse(StatusCode.SUCCESS)
                        : new CancelResponse(StatusCode.NOT_REGISTERED));
    }

    private static IllegalArgumentException unexpected(ProtocolMessage request) {
        return new IllegalArgumentException("Server does not accept " + request.type());
    }
}
