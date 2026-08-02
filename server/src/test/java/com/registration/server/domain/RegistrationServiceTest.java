package com.registration.server.domain;

import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.store.InMemoryRegistrationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationServiceTest {

    private static final int VALIDITY_PERIOD_SECONDS = 60;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        RegistrationProperties properties = new RegistrationProperties(0, VALIDITY_PERIOD_SECONDS, 1000);
        service = new RegistrationService(new InMemoryRegistrationStore(), properties);
    }

    @Test
    void registerNewClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");

        assertThat(service.handle(new RegisterRequest(clientId)))
                .isEqualTo(new RegisterResponse(StatusCode.SUCCESS, VALIDITY_PERIOD_SECONDS));
    }

    @Test
    void registerTwiceIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(new RegisterRequest(clientId));

        assertThat(service.handle(new RegisterRequest(clientId)))
                .isEqualTo(new RegisterResponse(StatusCode.ALREADY_REGISTERED, 0));
    }

    @Test
    void renewRegisteredClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(new RegisterRequest(clientId));

        assertThat(service.handle(new RenewRequest(clientId)))
                .isEqualTo(new RenewResponse(StatusCode.SUCCESS, VALIDITY_PERIOD_SECONDS));
    }

    @Test
    void renewUnregisteredClientIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");

        assertThat(service.handle(new RenewRequest(clientId)))
                .isEqualTo(new RenewResponse(StatusCode.NOT_REGISTERED, 0));
    }

    @Test
    void cancelRegisteredClientSucceeds() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(new RegisterRequest(clientId));

        assertThat(service.handle(new CancelRequest(clientId)))
                .isEqualTo(new CancelResponse(StatusCode.SUCCESS));
    }

    @Test
    void cancelUnregisteredClientIsRejected() {
        ClientId clientId = ClientId.parse("123456789012");

        assertThat(service.handle(new CancelRequest(clientId)))
                .isEqualTo(new CancelResponse(StatusCode.NOT_REGISTERED));
    }

    @Test
    void cancelledClientCanRegisterAgain() {
        ClientId clientId = ClientId.parse("123456789012");
        service.handle(new RegisterRequest(clientId));
        service.handle(new CancelRequest(clientId));

        assertThat(service.handle(new RegisterRequest(clientId)))
                .isEqualTo(new RegisterResponse(StatusCode.SUCCESS, VALIDITY_PERIOD_SECONDS));
    }
}
