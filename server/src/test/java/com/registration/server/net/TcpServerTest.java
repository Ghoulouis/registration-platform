package com.registration.server.net;

import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ChallengeResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.FrameDecoder;
import com.registration.common.protocol.MessageCodec;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.domain.RegistrationService;
import com.registration.server.store.InMemoryChallengeStore;
import com.registration.server.store.InMemoryRegistrationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.PrivateKey;

import static org.assertj.core.api.Assertions.assertThat;

class TcpServerTest {

    private static final int VALIDITY_PERIOD_SECONDS = 60;

    // Demo Shared Signing Key (ADR-0009); matches RegistrationProperties' default authPublicKey.
    private static final String PRIVATE_SEED_B64 = "pU55QBNBWdgYnCyCaZsfU3jImcyqZKGmSv3Nb+YEEbM=";
    private static final String PUBLIC_KEY_B64 = "OyqZa3x46M9IqazQAsypDYZr244z47nMSQVPmoK7Kcw=";

    private RegistrationProperties properties;
    private TcpServer server;
    private PrivateKey signingKey;

    @BeforeEach
    void startServer() throws IOException {
        int port = findFreePort();
        properties = new RegistrationProperties(port, VALIDITY_PERIOD_SECONDS, 1000, 30, PUBLIC_KEY_B64);
        RegistrationService registrationService =
                new RegistrationService(new InMemoryRegistrationStore(), new InMemoryChallengeStore(), properties);
        server = new TcpServer(properties, registrationService);
        server.start();
        signingKey = Ed25519.parsePrivateKey(PRIVATE_SEED_B64);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void registerThenRenewSucceeds() throws IOException {
        ClientId clientId = ClientId.parse("123456789012");

        assertThat(register(clientId)).isEqualTo(RegisterResponse.success(VALIDITY_PERIOD_SECONDS));
        assertThat(send(new RenewRequest(clientId)))
                .isEqualTo(new RenewResponse(StatusCode.SUCCESS, VALIDITY_PERIOD_SECONDS));
    }

    @Test
    void duplicateRegisterIsRejected() throws IOException {
        ClientId clientId = ClientId.parse("111111111111");
        register(clientId);

        assertThat(send(RegisterRequest.initial(clientId))).isEqualTo(RegisterResponse.alreadyRegistered());
    }

    @Test
    void renewWithoutRegisterIsRejected() throws IOException {
        ClientId clientId = ClientId.parse("222222222222");

        assertThat(send(new RenewRequest(clientId)))
                .isEqualTo(new RenewResponse(StatusCode.NOT_REGISTERED, 0));
    }

    @Test
    void cancelRegisteredClientSucceedsAndFreesTheId() throws IOException {
        ClientId clientId = ClientId.parse("333333333333");
        register(clientId);

        assertThat(send(new CancelRequest(clientId))).isEqualTo(new CancelResponse(StatusCode.SUCCESS));
        assertThat(register(clientId)).isEqualTo(RegisterResponse.success(VALIDITY_PERIOD_SECONDS));
    }

    @Test
    void cancelWithoutRegisterIsRejected() throws IOException {
        ClientId clientId = ClientId.parse("444444444444");

        assertThat(send(new CancelRequest(clientId))).isEqualTo(new CancelResponse(StatusCode.NOT_REGISTERED));
    }

    private RegisterResponse register(ClientId clientId) throws IOException {
        RegisterResponse challengeResponse = (RegisterResponse) send(RegisterRequest.initial(clientId));
        ChallengeResponse signature = Ed25519.sign(signingKey, challengeResponse.challenge());
        return (RegisterResponse) send(RegisterRequest.withChallengeResponse(clientId, signature));
    }

    private ProtocolMessage send(ProtocolMessage request) throws IOException {
        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", properties.tcpPort()))) {
            channel.write(MessageCodec.encode(request));

            FrameDecoder decoder = new FrameDecoder();
            ByteBuffer readBuffer = ByteBuffer.allocate(64);
            while (!decoder.isComplete()) {
                readBuffer.clear();
                int bytesRead = channel.read(readBuffer);
                if (bytesRead == -1) {
                    throw new IOException("Server closed connection before sending a full response");
                }
                readBuffer.flip();
                decoder.feed(readBuffer);
            }
            return decoder.decode();
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
