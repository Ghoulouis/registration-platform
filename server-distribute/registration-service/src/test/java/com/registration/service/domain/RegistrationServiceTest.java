package com.registration.service.domain;

import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.service.config.RegistrationProperties;
import com.registration.service.store.FakeRegistrationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.concurrent.ConcurrentHashMap;

class RegistrationServiceTest {

    private static final int VALIDITY_PERIOD_SECONDS = 60;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        RegistrationProperties properties = new RegistrationProperties(0, VALIDITY_PERIOD_SECONDS, 1);
        service = new RegistrationService(new FakeRegistrationStore(ConcurrentHashMap.newKeySet()), properties);
    }

    @Test
    void registerNewClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");

        StepVerifier.create(service.handle(new RegisterRequest(clientId)))
                .expectNext(new RegisterResponse(StatusCode.SUCCESS, VALIDITY_PERIOD_SECONDS))
                .verifyComplete();
    }

    @Test
    void registerTwiceIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(new RegisterRequest(clientId)).block();

        StepVerifier.create(service.handle(new RegisterRequest(clientId)))
                .expectNext(new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0))
                .verifyComplete();
    }

    @Test
    void renewRegisteredClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(new RegisterRequest(clientId)).block();

        StepVerifier.create(service.handle(new RenewRequest(clientId)))
                .expectNext(new RenewResponse(StatusCode.SUCCESS, VALIDITY_PERIOD_SECONDS))
                .verifyComplete();
    }

    @Test
    void renewUnregisteredClientIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");

        StepVerifier.create(service.handle(new RenewRequest(clientId)))
                .expectNext(new RenewResponse(StatusCode.NOT_REGISTERED, 0))
                .verifyComplete();
    }

    @Test
    void cancelRegisteredClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(new RegisterRequest(clientId)).block();

        StepVerifier.create(service.handle(new CancelRequest(clientId)))
                .expectNext(new CancelResponse(StatusCode.SUCCESS))
                .verifyComplete();
    }

    @Test
    void cancelUnregisteredClientIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");

        StepVerifier.create(service.handle(new CancelRequest(clientId)))
                .expectNext(new CancelResponse(StatusCode.NOT_REGISTERED))
                .verifyComplete();
    }

    @Test
    void cancelledClientCanRegisterAgain() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(new RegisterRequest(clientId)).block();
        service.handle(new CancelRequest(clientId)).block();

        StepVerifier.create(service.handle(new RegisterRequest(clientId)))
                .expectNext(new RegisterResponse(StatusCode.SUCCESS, VALIDITY_PERIOD_SECONDS))
                .verifyComplete();
    }
}
