package com.ridehailing.realtime.ws;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

/**
 * A session's sends, one at a time on a virtual thread of its own (LLD §14.7), so one session never waits for another.
 * A newer message with the same key replaces one not yet sent. More than the buffer limit waiting, or a send that
 * fails, which the container's send timeout bounds, ends the outbox with a status for the session.
 */
final class Outbox {

    /** Where the messages go; a send blocks until the frame is written or the send times out. */
    interface Socket {

        void send(WebSocketMessage<?> message) throws IOException;
    }

    static final CloseStatus TOO_SLOW = CloseStatus.SESSION_NOT_RELIABLE.withReason("Too slow");

    private static final long PING_BYTES = 2;

    private final Socket socket;
    private final long bufferLimit;
    private final Consumer<CloseStatus> onFailure;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private final Map<String, Item> unsentByKey = new HashMap<>();
    private long bytes;
    private boolean ended;

    Outbox(String name, Socket socket, long bufferLimit, Consumer<CloseStatus> onFailure) {
        this.socket = socket;
        this.bufferLimit = bufferLimit;
        this.onFailure = onFailure;
        Thread.ofVirtual().name(name).start(this::run);
    }

    void send(String text) {
        offer(null, new TextMessage(text), text.length());
    }

    /** Replaces an unsent message with the same key, which keeps that one's place. */
    void sendLatest(String key, String text) {
        offer(key, new TextMessage(text), text.length());
    }

    void ping() {
        offer(null, new PingMessage(), PING_BYTES);
    }

    /** Stops sending; what is waiting is dropped. */
    void end() {
        lock.lock();
        try {
            drop();
            ready.signalAll();
        } finally {
            lock.unlock();
        }
    }

    long waitingBytes() {
        lock.lock();
        try {
            return bytes;
        } finally {
            lock.unlock();
        }
    }

    private void offer(String key, WebSocketMessage<?> message, long size) {
        boolean overflowed;
        lock.lock();
        try {
            if (ended) {
                return;
            }
            Item unsent = key == null ? null : unsentByKey.get(key);
            if (unsent != null) {
                bytes += size - unsent.size;
                unsent.message = message;
                unsent.size = size;
            } else {
                Item item = new Item(key, message, size);
                queue.addLast(item);
                if (key != null) {
                    unsentByKey.put(key, item);
                }
                bytes += size;
            }
            overflowed = bytes > bufferLimit;
            if (overflowed) {
                drop();
            }
            ready.signalAll();
        } finally {
            lock.unlock();
        }
        if (overflowed) {
            onFailure.accept(TOO_SLOW);
        }
    }

    /** Under the lock. */
    private void drop() {
        ended = true;
        queue.clear();
        unsentByKey.clear();
        bytes = 0;
    }

    private void run() {
        while (true) {
            Item next;
            lock.lock();
            try {
                while (queue.isEmpty() && !ended) {
                    ready.awaitUninterruptibly();
                }
                if (ended) {
                    return;
                }
                next = queue.removeFirst();
                if (next.key != null) {
                    unsentByKey.remove(next.key);
                }
                bytes -= next.size;
            } finally {
                lock.unlock();
            }
            try {
                socket.send(next.message);
            } catch (IOException | RuntimeException e) {
                end();
                onFailure.accept(TOO_SLOW);
                return;
            }
        }
    }

    private static final class Item {

        private final String key;
        private WebSocketMessage<?> message;
        private long size;

        private Item(String key, WebSocketMessage<?> message, long size) {
            this.key = key;
            this.message = message;
            this.size = size;
        }
    }
}
