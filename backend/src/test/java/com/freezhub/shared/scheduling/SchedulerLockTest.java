package com.freezhub.shared.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** One instance at a time (FZ-121). */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class SchedulerLockTest {

    @Autowired
    private SchedulerLock lock;

    @Autowired
    private JdbcTemplate jdbc;

    private String uniqueJob() {
        return "job-" + System.nanoTime();
    }

    @Test
    void oneHolderAtATime() {
        String job = uniqueJob();
        AtomicInteger ran = new AtomicInteger();

        // The second call happens while the first still holds the lock.
        lock.runIfAcquired(job, Duration.ofMinutes(5), () -> {
            ran.incrementAndGet();
            assertThat(lock.runIfAcquired(job, Duration.ofMinutes(5), ran::incrementAndGet))
                    .isFalse();
        });

        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void theLockIsReleasedWhenTheWorkFinishes() {
        // A lease longer than the job's interval would otherwise stop the same instance
        // running again on its next tick — the lock would throttle the job it protects.
        String job = uniqueJob();

        lock.runIfAcquired(job, Duration.ofMinutes(5), () -> { });

        assertThat(lock.runIfAcquired(job, Duration.ofMinutes(5), () -> { })).isTrue();
    }

    @Test
    void aJobThatThrowsDoesNotKeepTheLock() {
        // A failing job holding its name for the rest of the lease would stop every
        // instance running it until the lease expired.
        String job = uniqueJob();

        try {
            lock.runIfAcquired(job, Duration.ofMinutes(5), () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // The work's failure belongs to the caller, not to the lock.
        }

        assertThat(lock.runIfAcquired(job, Duration.ofMinutes(5), () -> { })).isTrue();
    }

    @Test
    void anExpiredLeaseIsTakenByTheNextInstance() {
        // What makes an instance dying mid-sweep recoverable rather than permanent.
        String job = uniqueJob();
        lock.acquire(job, Duration.ofMinutes(5), "the-dead-instance");
        jdbc.update("update scheduler_lock set locked_until = now() - interval '1 minute' "
                + "where name = ?", job);

        assertThat(lock.runIfAcquired(job, Duration.ofMinutes(5), () -> { })).isTrue();
    }

    @Test
    void anOverrunningHolderCannotReleaseTheLockSomebodyElseTook() {
        /*
         * The reason the row carries a token. A job that overran its lease has already
         * lost the lock; releasing by name alone would cut short the instance that has
         * since taken it — turning a slow job into the duplicate delivery this exists to
         * prevent.
         */
        String job = uniqueJob();
        lock.acquire(job, Duration.ofSeconds(1), "the-slow-instance");
        jdbc.update("update scheduler_lock set locked_until = now() - interval '1 second' "
                + "where name = ?", job);
        assertThat(lock.acquire(job, Duration.ofMinutes(5), "the-new-instance")).isTrue();

        lock.release(job, "the-slow-instance");

        // The new holder still holds it.
        assertThat(lock.acquire(job, Duration.ofMinutes(5), "a-third-instance")).isFalse();
    }

    @Test
    void instancesRacingForTheSameJobProduceOneWinner() throws Exception {
        /*
         * The criterion this story exists for. Eight threads reach the same statement at
         * the same moment and exactly one does the work.
         *
         * Read-then-write would let several read an unlocked row and all take it, which is
         * why acquisition is a single INSERT … ON CONFLICT … WHERE — decided inside one
         * row lock rather than across two round trips.
         */
        String job = uniqueJob();
        int instances = 8;
        AtomicInteger ran = new AtomicInteger();
        CyclicBarrier startTogether = new CyclicBarrier(instances);

        ExecutorService pool = Executors.newFixedThreadPool(instances);
        try {
            List<Callable<Boolean>> attempts = Collections.nCopies(instances, () -> {
                startTogether.await();
                return lock.runIfAcquired(job, Duration.ofMinutes(5), () -> {
                    ran.incrementAndGet();
                    // Held long enough that every other thread's attempt overlaps it.
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            });

            long winners = pool.invokeAll(attempts).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception failed) {
                            throw new IllegalStateException(failed);
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .count();

            assertThat(winners).isEqualTo(1);
            assertThat(ran.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
