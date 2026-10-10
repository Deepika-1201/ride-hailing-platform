package com.ridehailing.location.index;

import com.ridehailing.location.LiveIndex;
import com.ridehailing.location.LocationProperties;
import com.ridehailing.platform.Role;
import com.ridehailing.platform.Valkey;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class LiveIndexConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ride.location.store", havingValue = "memory", matchIfMissing = true)
    LiveIndex inMemoryLiveIndex(LocationProperties properties, Environment environment, Clock clock) {
        if (properties.singleProcessCheck()) {
            requireApiAndDispatch(Binder.get(environment).bind("ride.roles", Bindable.setOf(Role.class))
                    .orElseGet(() -> EnumSet.allOf(Role.class)));
        }
        return new InMemoryLiveIndex(clock, properties.freshness(), properties.tombstoneTtl(), properties.quality());
    }

    /** Shared by every process, so any split of roles is correct (LLD §1.3). */
    @Bean
    @ConditionalOnProperty(name = "ride.location.store", havingValue = "valkey")
    LiveIndex valkeyLiveIndex(Valkey valkey, LocationProperties properties, Clock clock) {
        return new ValkeyLiveIndex(valkey, clock, properties.freshness(), properties.tombstoneTtl(),
                properties.quality());
    }

    /** The in-memory index is only right when the API and dispatch share it (LLD §1.3). */
    static void requireApiAndDispatch(Set<Role> roles) {
        if (!roles.contains(Role.API) || !roles.contains(Role.DISPATCH)) {
            throw new IllegalStateException("ride.location.store=memory keeps the live index inside one process, "
                    + "so that process needs both the api and dispatch roles; it has " + roles);
        }
    }
}
