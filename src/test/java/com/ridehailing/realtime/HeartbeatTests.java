package com.ridehailing.realtime;

import static com.ridehailing.support.OpenApiContract.assertAnswered;
import static org.assertj.core.api.Assertions.assertThat;

import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.TestRides;
import com.ridehailing.support.WsClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** LLD §14.4, §14.7: the node pings its sessions and closes one that stays silent, with heartbeats sped up. */
@TestPropertySource(properties = {
    "ride.realtime.heartbeat-every=100ms",
    "ride.realtime.ping-every=300ms",
    "ride.realtime.idle-timeout=1500ms"
})
class HeartbeatTests extends IntegrationTest {

    private static final int OPCODE_CLOSE = 0x8;
    private static final int OPCODE_PING = 0x9;

    @Autowired
    private TestRides rides;

    @Autowired
    private MeterRegistry meters;

    @Test
    void aClientThatAnswersPingsStaysConnected() {
        try (WsClient client = WsClient.connect(port, ticket())) {
            Eventually.within(Duration.ofSeconds(5), () -> assertThat(client.pings()).isGreaterThanOrEqualTo(6));

            assertThat(client.isOpen()).as("its pongs count as signs of life").isTrue();
        }
    }

    @Test
    void aClientThatSendsNothingIsClosedAsIdle() throws IOException {
        double idleBefore = idleCloses();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            handshake(socket, ticket());
            long started = System.nanoTime();

            int pings = 0;
            Integer closedWith = null;
            // Pings keep the socket busy, so its read timeout alone wouldn't end the wait if the close never came.
            while (closedWith == null && System.nanoTime() - started < Duration.ofSeconds(10).toNanos()) {
                Frame frame = Frame.read(socket.getInputStream());
                if (frame.opcode() == OPCODE_PING) {
                    pings++;
                } else if (frame.opcode() == OPCODE_CLOSE) {
                    closedWith = (frame.payload()[0] & 0xff) << 8 | frame.payload()[1] & 0xff;
                }
            }

            assertThat(closedWith).as("closed within 10 s").isEqualTo(4500);
            assertThat(pings).as("pinged while silent").isPositive();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(1_400));
        }
        Eventually.within(Duration.ofSeconds(5), () -> assertThat(idleCloses()).isEqualTo(idleBefore + 1));
    }

    private String ticket() {
        return assertAnswered("POST", "/v1/realtime/tickets", call("POST", rides.rider("Asha").authorization(),
                "/v1/realtime/tickets", null), 201).get("ticket").asString();
    }

    /** The opening handshake by hand, so nothing answers the server's pings. */
    private void handshake(Socket socket, String ticket) throws IOException {
        byte[] nonce = new byte[16];
        ThreadLocalRandom.current().nextBytes(nonce);
        OutputStream out = socket.getOutputStream();
        out.write(("GET /ws?ticket=" + ticket + " HTTP/1.1\r\nHost: localhost:" + port
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "
                + Base64.getEncoder().encodeToString(nonce) + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                throw new IOException("The server closed the handshake: " + head);
            }
            head.write(next);
        }
        assertThat(head.toString(StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 101");
    }

    private double idleCloses() {
        Counter counter = meters.find("websocket.closes").tag("reason", "idle").counter();
        return counter == null ? 0 : counter.count();
    }

    /** A frame from the server, which never masks and sends control frames of at most 125 bytes. */
    private record Frame(int opcode, byte[] payload) {

        static Frame read(InputStream in) throws IOException {
            int first = in.read();
            int second = in.read();
            if (first < 0 || second < 0) {
                throw new IOException("The connection ended without a close frame");
            }
            long length = second & 0x7f;
            if (length == 126) {
                length = (long) in.read() << 8 | in.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = length << 8 | in.read();
                }
            }
            return new Frame(first & 0x0f, in.readNBytes((int) length));
        }
    }
}
