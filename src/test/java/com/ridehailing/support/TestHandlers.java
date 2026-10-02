package com.ridehailing.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Scripted handlers present in every integration-test context, so the tests share one context per role set. */
@TestConfiguration(proxyBeanMethods = false)
public class TestHandlers {

    public static final String FIRST_CONSUMER = "test.first";
    public static final String SECOND_CONSUMER = "test.second";

    @Bean
    ScriptedConsumer firstConsumer(JdbcClient jdbc) {
        return new ScriptedConsumer(FIRST_CONSUMER, jdbc);
    }

    @Bean
    ScriptedConsumer secondConsumer(JdbcClient jdbc) {
        return new ScriptedConsumer(SECOND_CONSUMER, jdbc);
    }

    @Bean
    ScriptedTimerHandler timerHandler(JdbcClient jdbc) {
        return new ScriptedTimerHandler(jdbc);
    }
}
