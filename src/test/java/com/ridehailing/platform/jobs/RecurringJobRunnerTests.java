package com.ridehailing.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehailing.platform.InstanceId;
import com.ridehailing.platform.Leases;
import com.ridehailing.platform.RecurringJob;
import com.ridehailing.platform.Role;
import com.ridehailing.platform.roles.RoleProperties;
import com.ridehailing.platform.workers.WorkerProperties;
import com.ridehailing.support.Eventually;
import com.ridehailing.support.IntegrationTest;
import com.ridehailing.support.PlatformTables;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

/** LLD §5.7: a recurring job runs once per interval across the cluster, only where its role runs. */
@ExtendWith(OutputCaptureExtension.class)
class RecurringJobRunnerTests extends IntegrationTest {

    @Autowired
    private Leases leases;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RecurringJobRunner applicationRunner;

    @BeforeEach
    void reset() {
        PlatformTables.reset(jdbc);
    }

    @Test
    void aJobRunsOncePerIntervalAcrossNodes() {
        CountingJob job = new CountingJob("test-job", Role.WORKER);
        RecurringJobRunner nodeA = runner("node-a", Set.of(Role.WORKER), job);
        RecurringJobRunner nodeB = runner("node-b", Set.of(Role.WORKER), job);

        assertThat(nodeA.runIfDue(job)).isTrue();
        assertThat(nodeB.runIfDue(job)).isFalse();
        assertThat(nodeA.runIfDue(job)).isFalse();
        endInterval(job);
        assertThat(nodeB.runIfDue(job)).isTrue();

        assertThat(job.runs).hasValue(2);
    }

    @Test
    void aFailedRunIsLoggedAndTheJobRunsAgainAfterItsInterval() {
        CountingJob job = new CountingJob("failing-job", Role.WORKER);
        job.failing = true;
        RecurringJobRunner runner = runner("node-a", Set.of(Role.WORKER), job);

        assertThat(runner.runIfDue(job)).isTrue();
        assertThat(runner.runIfDue(job)).isFalse();
        endInterval(job);
        assertThat(runner.runIfDue(job)).isTrue();

        assertThat(job.runs).hasValue(2);
    }

    @Test
    void onlyJobsOfThisProcesssRolesAreScheduled() {
        CountingJob workerJob = new CountingJob("worker-job", Role.WORKER);
        CountingJob dispatchJob = new CountingJob("dispatch-job", Role.DISPATCH);

        RecurringJobRunner runner = runner("node-a", Set.of(Role.API, Role.DISPATCH), workerJob, dispatchJob);

        assertThat(runner.jobs()).containsExactly(dispatchJob);
    }

    @Test
    void onlyJobsRunningAtMostOnceAMinuteLogTheirRunsAtInfo(CapturedOutput output) {
        CountingJob hourly = new CountingJob("hourly-job", Role.WORKER);
        CountingJob everyMinute = new CountingJob("minutely-job", Role.WORKER, Duration.ofMinutes(1));
        CountingJob frequent = new CountingJob("frequent-job", Role.WORKER, Duration.ofSeconds(59));
        RecurringJobRunner runner = runner("node-a", Set.of(Role.WORKER), hourly, everyMinute, frequent);

        runner.runIfDue(hourly);
        runner.runIfDue(everyMinute);
        runner.runIfDue(frequent);

        assertThat(output).contains("Job hourly-job finished", "Job minutely-job finished")
                .doesNotContain("Job frequent-job finished");
        assertThat(frequent.runs).hasValue(1);
    }

    @Test
    void jobNamesMustBeUnique() {
        assertThatThrownBy(() -> runner("node-a", Set.of(Role.WORKER),
                new CountingJob("same", Role.WORKER), new CountingJob("same", Role.WORKER)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theApplicationSchedulesThePlatformAndAuditJobs() {
        assertThat(applicationRunner.jobs()).extracting(RecurringJob::name)
                .contains("platform-retention", "audit-partitions");
    }

    @Test
    void aStartedRunnerRunsItsJobsInTheBackgroundAndStops() {
        CountingJob job = new CountingJob("background-job", Role.WORKER);
        RecurringJobRunner runner = runner("node-a", Set.of(Role.WORKER), job);

        runner.start();
        try {
            Eventually.within(Duration.ofSeconds(5), () -> assertThat(job.runs).hasValue(1));
        } finally {
            runner.stop();
        }
        assertThat(runner.isRunning()).isFalse();
    }

    private RecurringJobRunner runner(String holder, Set<Role> roles, RecurringJob... jobs) {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        for (int index = 0; index < jobs.length; index++) {
            registry.registerSingleton("job" + index, jobs[index]);
        }
        return new RecurringJobRunner(registry.getBeanProvider(RecurringJob.class), new RoleProperties(roles), leases,
                new InstanceId(holder), new WorkerProperties(false));
    }

    private void endInterval(RecurringJob job) {
        jdbc.sql("UPDATE platform.leases SET expires_at = now() - interval '1 second' WHERE name = :name")
                .param("name", "job:" + job.name())
                .update();
    }

    static final class CountingJob implements RecurringJob {

        final AtomicInteger runs = new AtomicInteger();
        private final String name;
        private final Role role;
        private final Duration interval;
        volatile boolean failing;

        CountingJob(String name, Role role) {
            this(name, role, Duration.ofHours(1));
        }

        CountingJob(String name, Role role, Duration interval) {
            this.name = name;
            this.role = role;
            this.interval = interval;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Role role() {
            return role;
        }

        @Override
        public Duration interval() {
            return interval;
        }

        @Override
        public void run() {
            runs.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("scripted job failure");
            }
        }
    }
}
