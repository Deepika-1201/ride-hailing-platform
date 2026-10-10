package com.ridehailing.realtime.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.Eventually;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

/** LLD §14.4, §14.7: a session's sends go out in order, positions coalesce, and a session too slow to keep ends. */
class OutboxTests {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final FakeSocket socket = new FakeSocket();
    private final List<CloseStatus> failures = new CopyOnWriteArrayList<>();
    private Outbox outbox;

    @AfterEach
    void end() {
        socket.release();
        if (outbox != null) {
            outbox.end();
        }
    }

    @Test
    void messagesAndPingsGoOutInOrder() {
        outbox = outbox(1_024);

        outbox.send("a");
        outbox.ping();
        outbox.send("b");

        Eventually.within(WAIT, () -> assertThat(socket.sent()).containsExactly("a", "ping", "b"));
        assertThat(failures).isEmpty();
    }

    @Test
    void aNewerMessageWithTheKeyReplacesOneNotYetSentInItsPlace() throws InterruptedException {
        socket.holdSends();
        outbox = outbox(1_024);
        outbox.send("first");
        socket.awaitSending();

        outbox.sendLatest("ride:1", "p1");
        outbox.send("other");
        outbox.sendLatest("ride:1", "p2");
        outbox.sendLatest("ride:2", "q1");
        socket.release();

        Eventually.within(WAIT, () -> assertThat(socket.sent()).containsExactly("first", "p2", "other", "q1"));
        assertThat(outbox.waitingBytes()).isZero();
        outbox.sendLatest("ride:1", "p3");
        Eventually.within(WAIT, () -> assertThat(socket.sent()).as("a sent message's key is free again")
                .containsExactly("first", "p2", "other", "q1", "p3"));
    }

    @Test
    void moreThanTheBufferLimitWaitingEndsTheOutboxAsTooSlow() throws InterruptedException {
        socket.holdSends();
        outbox = outbox(10);
        outbox.send("sending");
        socket.awaitSending();

        outbox.send("12345");
        outbox.send("12345");
        assertThat(failures).as("the limit itself is allowed").isEmpty();
        outbox.send("1");

        assertThat(failures).containsExactly(Outbox.TOO_SLOW);
        assertThat(Outbox.TOO_SLOW.getCode()).isEqualTo(4500);
        assertThat(outbox.waitingBytes()).isZero();
        socket.release();
        outbox.send("after");
        Eventually.within(WAIT, () -> assertThat(socket.sent()).containsExactly("sending"));
    }

    @Test
    void aFailedSendEndsTheOutboxAsTooSlow() {
        socket.failSends();
        outbox = outbox(1_024);

        outbox.send("lost");

        Eventually.within(WAIT, () -> assertThat(failures).containsExactly(Outbox.TOO_SLOW));
        outbox.send("after");
        assertThat(outbox.waitingBytes()).isZero();
    }

    @Test
    void endingDropsWhatIsWaitingWithoutAFailure() throws InterruptedException {
        socket.holdSends();
        outbox = outbox(1_024);
        outbox.send("sending");
        socket.awaitSending();
        outbox.send("waiting");

        outbox.end();
        socket.release();

        Eventually.within(WAIT, () -> assertThat(socket.sent()).containsExactly("sending"));
        assertThat(failures).isEmpty();
    }

    private Outbox outbox(long bufferLimit) {
        return new Outbox("outbox-test", socket, bufferLimit, failures::add);
    }

    /** Records what was sent; can hold every send until released, or fail them. */
    private static final class FakeSocket implements Outbox.Socket {

        private final List<String> sent = new CopyOnWriteArrayList<>();
        private final CountDownLatch sending = new CountDownLatch(1);
        private volatile CountDownLatch released = new CountDownLatch(0);
        private volatile boolean failing;

        @Override
        public void send(WebSocketMessage<?> message) throws IOException {
            sending.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (failing) {
                throw new IOException("The peer went away");
            }
            sent.add(message instanceof TextMessage text ? text.getPayload() : "ping");
        }

        void holdSends() {
            released = new CountDownLatch(1);
        }

        void awaitSending() throws InterruptedException {
            assertThat(sending.await(5, TimeUnit.SECONDS)).as("a send began").isTrue();
        }

        void release() {
            released.countDown();
        }

        void failSends() {
            failing = true;
        }

        List<String> sent() {
            return List.copyOf(sent);
        }
    }
}
