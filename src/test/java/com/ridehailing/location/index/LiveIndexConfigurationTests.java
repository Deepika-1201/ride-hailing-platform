package com.ridehailing.location.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LocationProperties;
import com.ridehailing.platform.Role;
import com.ridehailing.platform.Valkey;
import com.ridehailing.support.Valkeys;
import java.time.Clock;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** LLD §1.3: the in-memory live index is only right in a process that runs both the API and dispatch. */
class LiveIndexConfigurationTests {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(LocationPropertiesOn.class, LiveIndexConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void aProcessWithTheApiAndDispatchMayKeepTheIndexInMemory() {
        assertThatCode(() -> LiveIndexConfiguration.requireApiAndDispatch(EnumSet.of(Role.API, Role.DISPATCH)))
                .doesNotThrowAnyException();
        assertThatCode(() -> LiveIndexConfiguration.requireApiAndDispatch(EnumSet.allOf(Role.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void aProcessWithoutEitherRefusesToStart() {
        assertThatIllegalStateException()
                .isThrownBy(() -> LiveIndexConfiguration.requireApiAndDispatch(EnumSet.of(Role.API, Role.WORKER)))
                .withMessageContaining("api and dispatch");
        assertThatIllegalStateException()
                .isThrownBy(() -> LiveIndexConfiguration.requireApiAndDispatch(EnumSet.of(Role.DISPATCH)));
    }

    @Test
    void theDefaultProcessRunsEveryRoleAndGetsTheInMemoryIndex() {
        contexts.run(context -> assertThat(context).hasSingleBean(InMemoryLiveIndex.class));
    }

    @Test
    void theQualityRulesDefaultToTheDesignsThresholds() {
        contexts.run(context -> assertThat(context.getBean(LocationProperties.class).quality())
                .isEqualTo(LiveIndex.Quality.DEFAULT));
    }

    @Test
    void aSingleRoleProcessFailsToStartUnlessTheCheckIsOff() {
        contexts.withPropertyValues("ride.roles=worker").run(context -> assertThat(context).hasFailed());
        contexts.withPropertyValues("ride.roles=worker", "ride.location.single-process-check=false")
                .run(context -> assertThat(context).hasSingleBean(LiveIndex.class));
    }

    @Test
    void valkeyHoldsTheIndexForAnySplitOfRoles() {
        // The shared client outlives this context, so the context mustn't close it.
        contexts.withBean("valkey", Valkey.class, Valkeys::standalone, bean -> bean.setDestroyMethodName(""))
                .withPropertyValues("ride.location.store=valkey", "ride.roles=worker")
                .run(context -> assertThat(context).hasSingleBean(ValkeyLiveIndex.class));
    }

    @Test
    void anUnknownStoreFailsStartup() {
        contexts.withPropertyValues("ride.location.store=redis").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("memory or valkey"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LocationProperties.class)
    static class LocationPropertiesOn {
    }
}
