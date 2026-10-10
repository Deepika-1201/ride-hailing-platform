package com.ridehailing.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * A WebSocket client on the JDK's (LLD §17.1). It keeps every message the server sends, each checked against
 * {@code server-messages.v1.json}, the pings it answered, and how the server closed the connection.
 */
public final class WsClient implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final List<AssertionError> violations = new CopyOnWriteArrayList<>();
    private final AtomicInteger pings = new AtomicInteger();
    private final CompletableFuture<Integer> closedWith = new CompletableFuture<>();
    private WebSocket socket;

    private WsClient() {
    }

    /** {@code ws://localhost:<port>/ws?ticket=…}. */
    public static WsClient connect(int port, String ticket) {
        WsClient client = new WsClient();
        client.socket = HTTP.newWebSocketBuilder().connectTimeout(WAIT)
                .buildAsync(uri(port, ticket), client.new Listener()).join();
        return client;
    }

    /** The status that refused the handshake, such as 401 or 503. */
    public static int refusal(int port, String ticket) {
        try {
            HTTP.newWebSocketBuilder().connectTimeout(WAIT).buildAsync(uri(port, ticket), new WebSocket.Listener() {
            }).join().abort();
        } catch (CompletionException e) {
            if (e.getCause() instanceof WebSocketHandshakeException refused) {
                return refused.getResponse().statusCode();
            }
            throw e;
        }
        throw new AssertionError("The handshake was accepted");
    }

    public void send(String text) {
        socket.sendText(text, true).join();
    }

    public void sendBinary(byte[] bytes) {
        socket.sendBinary(ByteBuffer.wrap(bytes), true).join();
    }

    /** Every message so far, oldest first. */
    public List<JsonNode> messages() {
        assertConformed();
        return List.copyOf(received);
    }

    public List<JsonNode> messages(String type) {
        return messages().stream().filter(message -> message.get("type").asString().equals(type)).toList();
    }

    /** The first message of the type, once it arrives. */
    public JsonNode await(String type) {
        Eventually.within(WAIT, () -> assertThat(messages(type)).as("a %s message", type).isNotEmpty());
        return messages(type).getFirst();
    }

    /** Waits until {@code count} messages of the type arrived; answers them all. */
    public List<JsonNode> await(String type, int count) {
        Eventually.within(WAIT, () -> assertThat(messages(type)).as("%s messages", type).hasSizeGreaterThanOrEqualTo(
                count));
        return messages(type);
    }

    /** The first message of the type whose field has the value, once it arrives. */
    public JsonNode await(String type, String field, String value) {
        Eventually.within(WAIT, () -> assertThat(messages(type)).as("a %s message with %s %s", type, field, value)
                .anyMatch(message -> message.has(field) && message.get(field).asString().equals(value)));
        return messages(type).stream().filter(message -> message.has(field)
                && message.get(field).asString().equals(value)).findFirst().orElseThrow();
    }

    public int pings() {
        return pings.get();
    }

    public boolean isOpen() {
        return !closedWith.isDone();
    }

    /** The code the server closed the connection with. */
    public int awaitClose() {
        try {
            return closedWith.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("The server didn't close the connection", e);
        }
    }

    @Override
    public void close() {
        if (isOpen()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(failure -> null).join();
        }
        socket.abort();
        assertConformed();
    }

    private void assertConformed() {
        if (!violations.isEmpty()) {
            throw violations.getFirst();
        }
    }

    private static URI uri(int port, String ticket) {
        return URI.create("ws://localhost:" + port + "/ws" + (ticket == null ? "" : "?ticket=" + ticket));
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    received.add(WebSocketContract.assertConforms(partial.toString()));
                } catch (AssertionError e) {
                    violations.add(e);
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            pings.incrementAndGet();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closedWith.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closedWith.complete(-1);
        }
    }
}
