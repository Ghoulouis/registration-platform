package com.registration.client.testsupport;

import com.registration.common.protocol.FrameDecoder;
import com.registration.common.protocol.MessageCodec;
import com.registration.common.protocol.ProtocolMessage;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A tiny single-connection-at-a-time TCP server for exercising Client code against
 * scripted behaviors (drop the connection, sleep past the timeout, or respond) that a
 * real Server + Redis wouldn't let us control deterministically in a test.
 */
public final class ScriptedTcpServer implements AutoCloseable {

    public enum Action {
        DROP, RESPOND
    }

    private final ServerSocket serverSocket;
    private final Queue<Behavior> script = new ConcurrentLinkedQueue<>();
    private final Thread acceptThread;
    private volatile boolean running = true;

    public ScriptedTcpServer() throws IOException {
        serverSocket = new ServerSocket(0);
        acceptThread = new Thread(this::acceptLoop, "scripted-tcp-server");
        acceptThread.start();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Each call to this server consumes one scripted behavior, in order. */
    public void enqueue(Behavior behavior) {
        script.add(behavior);
    }

    private void acceptLoop() {
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                handleConnection(socket);
            } catch (IOException e) {
                if (running) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void handleConnection(Socket socket) throws IOException {
        FrameDecoder decoder = new FrameDecoder();
        byte[] buffer = new byte[64];
        while (!decoder.isComplete()) {
            int n = socket.getInputStream().read(buffer);
            if (n == -1) {
                return;
            }
            decoder.feed(ByteBuffer.wrap(buffer, 0, n));
        }
        decoder.decode(); // consume the request; the scripted behavior doesn't depend on its content

        Behavior behavior = script.poll();
        if (behavior == null) {
            throw new IllegalStateException("No more scripted behaviors queued");
        }

        switch (behavior.action()) {
            case DROP -> {
                sleepUninterruptibly(behavior.dropAfterMillis());
                // let try-with-resources close the socket without writing a response
            }
            case RESPOND -> {
                ByteBuffer frame = MessageCodec.encode(behavior.response());
                socket.getOutputStream().write(frame.array(), frame.arrayOffset() + frame.position(), frame.remaining());
                socket.getOutputStream().flush();
            }
        }
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
        acceptThread.interrupt();
    }

    public record Behavior(Action action, ProtocolMessage response, long dropAfterMillis) {
        public static Behavior respond(ProtocolMessage response) {
            return new Behavior(Action.RESPOND, response, 0);
        }

        public static Behavior dropAfter(long millis) {
            return new Behavior(Action.DROP, null, millis);
        }
    }
}
