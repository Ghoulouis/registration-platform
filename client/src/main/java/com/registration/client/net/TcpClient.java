package com.registration.client.net;

import com.registration.common.protocol.FrameDecoder;
import com.registration.common.protocol.MessageCodec;
import com.registration.common.protocol.ProtocolMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * One short-lived, blocking TCP call: connect, send one request, read one response,
 * close (ADR-0001, ADR-0003). Plain blocking I/O — safe and cheap on a virtual thread
 * (ADR-0006), no NIO needed here.
 */
public final class TcpClient {

    private static final int READ_BUFFER_SIZE = 64;

    private final String host;
    private final int port;
    private final Duration timeout;

    public TcpClient(String host, int port, Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    /**
     * @throws java.net.SocketTimeoutException if connecting or reading the response takes
     *         longer than the configured timeout — callers distinguish this from other
     *         {@link IOException}s to classify timeouts vs. connection errors.
     */
    public ProtocolMessage send(ProtocolMessage request) throws IOException {
        try (Socket socket = new Socket()) {
            int timeoutMillis = Math.toIntExact(timeout.toMillis());
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            writeFrame(socket.getOutputStream(), request);
            return readFrame(socket.getInputStream());
        }
    }

    private static void writeFrame(OutputStream out, ProtocolMessage request) throws IOException {
        ByteBuffer frame = MessageCodec.encode(request);
        out.write(frame.array(), frame.arrayOffset() + frame.position(), frame.remaining());
        out.flush();
    }

    private static ProtocolMessage readFrame(InputStream in) throws IOException {
        FrameDecoder decoder = new FrameDecoder();
        byte[] readBuffer = new byte[READ_BUFFER_SIZE];
        while (!decoder.isComplete()) {
            int bytesRead = in.read(readBuffer);
            if (bytesRead == -1) {
                throw new EOFException("Server closed connection before sending a full response");
            }
            decoder.feed(ByteBuffer.wrap(readBuffer, 0, bytesRead));
        }
        return decoder.decode();
    }
}
