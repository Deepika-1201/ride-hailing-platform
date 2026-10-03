package com.ridehailing.platform.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.ridehailing.platform.Poller;
import com.ridehailing.platform.Role;
import com.ridehailing.platform.roles.RoleProperties;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PollerRunnerTests {

    @Test
    void runsOnlyThePollersOfThisProcesssRoles() {
        Poller dispatch = poller("dispatch-poller", Role.DISPATCH);
        Poller worker = poller("worker-poller", Role.WORKER);

        PollerRunner runner = runner(Set.of(Role.API, Role.DISPATCH), dispatch, worker);

        assertThat(runner.pollers()).containsExactly(dispatch);
    }

    @Test
    void refusesTwoPollersWithOneName() {
        assertThatIllegalStateException()
                .isThrownBy(() -> runner(Set.of(Role.DISPATCH), poller("same", Role.DISPATCH),
                        poller("same", Role.DISPATCH)))
                .withMessageContaining("same");
    }

    @Test
    void aNameMayRepeatAcrossRolesThatDontRunTogether() {
        Poller dispatch = poller("same", Role.DISPATCH);

        assertThat(runner(Set.of(Role.DISPATCH), dispatch, poller("same", Role.WORKER)).pollers())
                .containsExactly(dispatch);
    }

    @Test
    void startsOnItsOwnUnlessAutostartIsOff() {
        assertThat(new PollerRunner(new StaticListableBeanFactory().getBeanProvider(Poller.class),
                new RoleProperties(Set.of(Role.API)), new WorkerProperties(false)).isAutoStartup()).isFalse();
        assertThat(new PollerRunner(new StaticListableBeanFactory().getBeanProvider(Poller.class),
                new RoleProperties(Set.of(Role.API)), new WorkerProperties(true)).isAutoStartup()).isTrue();
    }

    private static PollerRunner runner(Set<Role> roles, Poller... pollers) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int index = 0; index < pollers.length; index++) {
            beans.addBean("poller" + index, pollers[index]);
        }
        return new PollerRunner(beans.getBeanProvider(Poller.class), new RoleProperties(roles),
                new WorkerProperties(true));
    }

    private static Poller poller(String name, Role role) {
        return new Poller() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Role role() {
                return role;
            }

            @Override
            public int threads() {
                return 1;
            }

            @Override
            public Duration interval() {
                return Duration.ofSeconds(1);
            }

            @Override
            public boolean poll() {
                return false;
            }
        };
    }
}
