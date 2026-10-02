package spike.ws;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * One app, three setups: SPRING_MAIN_WEB_APPLICATION_TYPE=servlet runs Tomcat with virtual threads
 * (SPIKE_TUNED=true shrinks its per-connection buffers to 1 KB), =reactive runs Netty.
 * Each connection gets an "ack" for every location update it sends.
 */
@SpringBootApplication
public class WsSpikeApplication {
    public static void main(String[] args) {
        SpringApplication.run(WsSpikeApplication.class, args);
    }
}

@Component
class Connections {
    final AtomicInteger open = new AtomicInteger();
    final LongAdder messages = new LongAdder();
}

@Configuration
@EnableWebSocket
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ServletWebSocketConfig implements WebSocketConfigurer {
    private final Connections connections;

    ServletWebSocketConfig(Connections connections) {
        this.connections = connections;
    }

    // Location updates are ~150 bytes; Tomcat's defaults reserve 8 KB per buffer per connection.
    @Bean
    @ConditionalOnProperty("spike.tuned")
    ServletServerContainerFactoryBean smallMessageBuffers() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024);
        container.setMaxBinaryMessageBufferSize(1024);
        return container;
    }

    @Bean
    @ConditionalOnProperty("spike.tuned")
    TomcatConnectorCustomizer smallSocketBuffers() {
        return connector -> {
            connector.setProperty("socket.appReadBufSize", "1024");
            connector.setProperty("socket.appWriteBufSize", "1024");
        };
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) {
                connections.open.incrementAndGet();
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                connections.open.decrementAndGet();
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
                connections.messages.increment();
                session.sendMessage(new TextMessage("ack"));
            }
        }, "/ws");
    }
}

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class ReactiveWebSocketConfig {
    @Bean
    HandlerMapping webSocketMapping(Connections connections) {
        org.springframework.web.reactive.socket.WebSocketHandler handler = session -> {
            connections.open.incrementAndGet();
            return session.send(session.receive().map(message -> {
                        connections.messages.increment();
                        return session.textMessage("ack");
                    }))
                    .doFinally(signal -> connections.open.decrementAndGet());
        };
        return new SimpleUrlHandlerMapping(Map.of("/ws", handler), -1);
    }
}

@RestController
class StatsController {
    private final Connections connections;

    StatsController(Connections connections) {
        this.connections = connections;
    }

    /** Memory after a full GC, so the numbers reflect live data rather than garbage. */
    @GetMapping("/stats")
    Map<String, Object> stats() throws IOException {
        System.gc();
        var memory = ManagementFactory.getMemoryMXBean();
        long rssKb = Files.readAllLines(Path.of("/proc/self/status")).stream()
                .filter(line -> line.startsWith("VmRSS:"))
                .mapToLong(line -> Long.parseLong(line.replaceAll("\\D", "")))
                .findFirst().orElse(-1);
        long direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct"))
                .mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("open", connections.open.get());
        out.put("messages", connections.messages.sum());
        out.put("heapUsedMb", memory.getHeapMemoryUsage().getUsed() >> 20);
        out.put("nonHeapUsedMb", memory.getNonHeapMemoryUsage().getUsed() >> 20);
        out.put("directBuffersMb", direct >> 20);
        out.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        out.put("rssMb", rssKb >> 10);
        return out;
    }
}
