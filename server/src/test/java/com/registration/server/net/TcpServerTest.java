package com.registration.server.net;

import com.registration.common.crypto.Ed25519;
import com.registration.common.protocol.CancelRequest;
import com.registration.common.protocol.CancelResponse;
import com.registration.common.protocol.ClientId;
import com.registration.common.protocol.FrameDecoder;
import com.registration.common.protocol.MessageCodec;
import com.registration.common.protocol.Nonce;
import com.registration.common.protocol.NonceSignature;
import com.registration.common.protocol.ProtocolMessage;
import com.registration.common.protocol.RegisterRequest;
import com.registration.common.protocol.RegisterResponse;
import com.registration.common.protocol.RenewRequest;
import com.registration.common.protocol.RenewResponse;
import com.registration.common.protocol.StatusCode;
import com.registration.common.protocol.TraceContext;
import com.registration.server.config.RegistrationProperties;
import com.registration.server.domain.RegistrationService;
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
        RegistrationService registrationService = new RegistrationService(new InMemoryRegistrationStore(), properties);
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
        Nonce nonce = register(clientId).nonce();

        RenewResponse response = renew(clientId, nonce);

        assertThat(response.status()).isEqualTo(StatusCode.SUCCESS);
        assertThat(response.validityPeriodSeconds()).isEqualTo(VALIDITY_PERIOD_SECONDS);
        assertThat(response.nonce()).isNotEqualTo(nonce);
    }

    @Test
    void duplicateRegisterIsRejected() throws IOException {
        ClientId clientId = ClientId.parse("111111111111");
        register(clientId);

        RegisterResponse response = (RegisterResponse) send(RegisterRequest.initial(clientId, TraceContext.newTrace()));

        assertThat(response.status()).isEqualTo(StatusCode.ALREADY_REGISTERED);
    }

    @Test
    void renewWithoutRegisterIsRejected() throws IOException {
        ClientId clientId = ClientId.parse("222222222222");

        assertThat(renew(clientId, Nonce.random())).isEqualTo(RenewResponse.notRegistered());
    }

    @Test
    void cancelRegisteredClientSucceedsAndFreesTheId() throws IOException {
        ClientId clientId = ClientId.parse("333333333333");
        Nonce nonce = register(clientId).nonce();

        assertThat(cancel(clientId, nonce)).isEqualTo(CancelResponse.success());
        assertThat(register(clientId).status()).isEqualTo(StatusCode.SUCCESS);
    }

    @Test
    void cancelWithoutRegisterIsRejected() throws IOException {
        ClientId clientId = ClientId.parse("444444444444");

        assertThat(cancel(clientId, Nonce.random())).isEqualTo(CancelResponse.notRegistered());
    }

    @Test
    void malformedFrameClosesOnlyThatConnectionAndServerKeepsRunning() throws IOException {
        ByteBuffer badFrame = ByteBuffer.allocate(5)
                .put((byte) 0x7F) // unknown MessageType code
                .putInt(0)
                .flip();

        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", properties.tcpPort()))) {
            channel.write(badFrame);

            ByteBuffer readBuffer = ByteBuffer.allocate(64);
            int bytesRead = channel.read(readBuffer);
            assertThat(bytesRead).isEqualTo(-1); // Server closed the connection rather than hanging or crashing.
        }

        // The reactor thread must still be alive and serving other connections.
        ClientId clientId = ClientId.parse("666666666666");
        assertThat(register(clientId).status()).isEqualTo(StatusCode.SUCCESS);
    }

    private RegisterResponse register(ClientId clientId) throws IOException {
        RegisterResponse challengeResponse = (RegisterResponse) send(RegisterRequest.initial(clientId, TraceContext.newTrace()));
        var signature = Ed25519.sign(signingKey, challengeResponse.nonce());
        return (RegisterResponse) send(RegisterRequest.withNonceSignature(clientId, TraceContext.newTrace(), signature));
    }

    private RenewResponse renew(ClientId clientId, Nonce nonceToSign) throws IOException {
        NonceSignature signature = Ed25519.sign(signingKey, nonceToSign);
        return (RenewResponse) send(new RenewRequest(clientId, TraceContext.newTrace(), signature));
    }

    private CancelResponse cancel(ClientId clientId, Nonce nonceToSign) throws IOException {
        NonceSignature signature = Ed25519.sign(signingKey, nonceToSign);
        return (CancelResponse) send(new CancelRequest(clientId, TraceContext.newTrace(), signature));
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
