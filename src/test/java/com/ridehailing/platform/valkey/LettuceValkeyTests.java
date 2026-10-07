package com.ridehailing.platform.valkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ridehailing.platform.Valkey;
import com.ridehailing.platform.Valkey.Script;
import com.ridehailing.support.Valkeys;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-020: scripts by {@code EVALSHA} with an {@code EVAL} retry, loaded on every primary; each wait bounded. */
class LettuceValkeyTests {

    private static final Script SWEEP = Script.named("live_sweep");
    private static final Duration SHORT = Duration.ofMillis(200);

    @Test
    void aScriptIsNamedByTheSha1OfItsSource() {
        assertThat(SWEEP.sha()).hasSize(40);
        String loaded = Valkeys.standalone().await("test", Valkeys.TIMEOUTS.other(),
                Valkeys.standalone().commands().scriptLoad(SWEEP.source()));
        assertThat(SWEEP.sha()).isEqualTo(loaded);
    }

    @Test
    void aScriptTheNodeLacksIsSentAgainWithoutCountingAnError() {
        LettuceValkey valkey = Valkeys.standalone();
        valkey.await("test", Valkeys.TIMEOUTS.other(), valkey.commands().scriptFlush());
        double errors = Valkeys.errors(SWEEP.name());

        List<String> swept = valkey.run(SWEEP, ScriptOutputType.MULTI, Valkeys.TIMEOUTS.other(),
                new String[] {"{lettuce-test}:seen"}, "1000", "0");

        assertThat(swept).isEmpty();
        assertThat(Valkeys.errors(SWEEP.name())).isEqualTo(errors);
        assertThat(valkey.await("test", Valkeys.TIMEOUTS.other(), valkey.commands().scriptExists(SWEEP.sha())))
                .containsExactly(true);
    }

    @Test
    void aCallWaitsNoLongerThanItsTimeoutAndCountsTheFailure() {
        try (LettuceValkey valkey = Valkeys.newStandalone(new Valkey.Timeouts(SHORT, SHORT, SHORT, SHORT))) {
            valkey.load(SWEEP);
            double errors = Valkeys.errors(SWEEP.name());

            Valkeys.whilePaused(() -> {
                long started = System.nanoTime();
                assertThatExceptionOfType(RedisCommandTimeoutException.class).isThrownBy(() -> valkey.run(SWEEP,
                        ScriptOutputType.MULTI, SHORT, new String[] {"{lettuce-test}:seen"}, "1000", "0"));
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(SHORT, Duration.ofSeconds(1));
            });

            assertThat(Valkeys.errors(SWEEP.name())).isEqualTo(errors + 1);
        }
    }

    @Test
    void scriptsLoadOnEveryPrimaryOfACluster() {
        LettuceValkey cluster = Valkeys.cluster();
        List<RedisClusterAsyncCommands<String, String>> primaries = cluster.primaries();
        primaries.forEach(primary -> cluster.await("test", Valkeys.TIMEOUTS.other(), primary.scriptFlush()));

        cluster.load(SWEEP);

        assertThat(primaries).hasSize(3).allSatisfy(primary -> assertThat(cluster.await("test",
                Valkeys.TIMEOUTS.other(), primary.scriptExists(SWEEP.sha()))).containsExactly(true));
    }
}
