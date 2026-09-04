package com.freezhub.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The counter itself, against an explicit clock (FZ-087). */
class RateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    @Test
    void allowsUpToTheLimitAndThenRefuses() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(1), 100);

        assertThat(limiter.check("1.2.3.4", NOW)).isEmpty();
        assertThat(limiter.check("1.2.3.4", NOW)).isEmpty();
        assertThat(limiter.check("1.2.3.4", NOW)).isEmpty();
        assertThat(limiter.check("1.2.3.4", NOW)).isPresent();
    }

    @Test
    void countsEachClientSeparately() {
        // The whole point: one abusive caller must not refuse everybody else.
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), 100);

        assertThat(limiter.check("1.2.3.4", NOW)).isEmpty();
        assertThat(limiter.check("1.2.3.4", NOW)).isPresent();
        assertThat(limiter.check("5.6.7.8", NOW)).isEmpty();
    }

    @Test
    void theWindowResets() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), 100);

        assertThat(limiter.check("1.2.3.4", NOW)).isEmpty();
        assertThat(limiter.check("1.2.3.4", NOW.plusSeconds(30))).isPresent();
        assertThat(limiter.check("1.2.3.4", NOW.plusSeconds(61))).isEmpty();
    }

    @Test
    void retryAfterIsNeverZero() {
        // A Retry-After of 0 invites the immediate retry this exists to prevent.
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(1), 100);

        limiter.check("1.2.3.4", NOW);
        Optional<Duration> retryAfter = limiter.check("1.2.3.4", NOW.plusMillis(999));

        assertThat(retryAfter).isPresent();
        assertThat(retryAfter.get()).isPositive();
    }

    @Test
    void doesNotGrowWithoutBound() {
        // The key comes from something the caller influences, so an unbounded map is a
        // memory-exhaustion target. Expired windows are dropped when the cap is reached.
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(10), 50);

        for (int i = 0; i < 50; i++) {
            limiter.check("client-" + i, NOW);
        }
        assertThat(limiter.trackedClients()).isEqualTo(50);

        limiter.check("late-arrival", NOW.plusSeconds(11));

        assertThat(limiter.trackedClients()).isLessThanOrEqualTo(2);
    }

    @Test
    void concurrentCallersCannotExceedTheLimit() throws Exception {
        // What this shows: the limit holds when many threads hit one key at once.
        //
        // What it does NOT show, checked rather than assumed: replacing the atomic
        // compute() with a get-then-put still passes this test. The race it introduces —
        // two threads both finding no counter, both creating one, one of them then
        // incrementing an orphan that is no longer in the map — is microseconds wide and
        // this cannot reliably provoke it. compute() stays because it is correct, not
        // because a test caught the alternative.
        int limit = 50;
        RateLimiter limiter = new RateLimiter(limit, Duration.ofMinutes(1), 100);
        int threads = 16;
        int attemptsPerThread = 20;

        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < attemptsPerThread; i++) {
                    if (limiter.check("same-client", NOW).isEmpty()) {
                        allowed.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(allowed.get()).isEqualTo(limit);
    }
}
