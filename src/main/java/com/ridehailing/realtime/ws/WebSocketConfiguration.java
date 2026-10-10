package com.ridehailing.realtime.ws;

import com.ridehailing.platform.ConditionalOnRole;
import com.ridehailing.platform.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * {@code /ws} on the {@code realtime} role (LLD §14.7). Any origin may connect: a single-use ticket authenticates the
 * handshake, not a cookie. Frames are at most 1 KB (§14.2).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnRole(Role.REALTIME)
class WebSocketConfiguration implements WebSocketConfigurer {

    static final String PATH = "/ws";
    static final int MAX_FRAME_BYTES = 1024;

    private final RealtimeHandler handler;
    private final TicketHandshake handshake;

    WebSocketConfiguration(RealtimeHandler handler, TicketHandshake handshake) {
        this.handler = handler;
        this.handshake = handshake;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PATH).addInterceptors(handshake).setAllowedOriginPatterns("*");
    }

    @Bean
    static ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_FRAME_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_FRAME_BYTES);
        return container;
    }
}
