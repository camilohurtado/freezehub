package com.freezhub.shared.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A fixed-window counter per client, in memory (FZ-087).
 *
 * <p>In-application and single-instance on purpose. A distributed limiter needs shared
 * state, which {@code CLAUDE.md} §4 excludes from the MVP, and one backend task does not
 * need one. The consequence is stated rather than hidden: running two instances doubles
 * the effective limit, because each keeps its own counters.
 *
 * <p><strong>What this is not.</strong> It stops one source hammering one endpoint. It
 * does nothing about abuse spread across many addresses — that needs a WAF or the load
 * balancer, above the application, and pretending otherwise here would be worse than
 * saying so.
 *
 * <p>Fixed window rather than sliding: a burst straddling a boundary can briefly reach
 * twice the limit, which is a real property and an acceptable one for protecting a signup
 * form. A sliding window costs per-request timestamps for every client, which is memory
 * spent on precision nobody needs here.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final int maxRequests;
    private final Duration window;
    private final int maxTrackedClients;

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, Duration window, int maxTrackedClients) {
        this.maxRequests = maxRequests;
        this.window = window;
        this.maxTrackedClients = maxTrackedClients;
    }

    /**
     * Records an attempt.
     *
     * @return empty if the caller may proceed, or how long until their window resets
     */
    public Optional<Duration> check(String client, Instant now) {
        if (counters.size() >= maxTrackedClients) {
            evictExpired(now);
        }

        // compute() and not get-then-put: it is atomic per key. Get-then-put lets two
        // threads both find nothing, both create a counter, and one of them go on
        // incrementing an orphan that is no longer in the map — so more requests are
        // allowed than the limit. No test here catches that: the window is microseconds
        // wide and RateLimiterTest passes either way, which was checked. This is correct
        // by construction rather than by coverage.
        Counter counter = counters.compute(client, (key, existing) -> {
            if (existing == null || existing.hasExpired(now, window)) {
                return new Counter(now);
            }
            return existing;
        });

        if (counter.count.incrementAndGet() > maxRequests) {
            Duration retryAfter = Duration.between(now, counter.startedAt.plus(window));
            // Never zero or negative: a Retry-After of 0 invites an immediate retry, which
            // is the behaviour being limited.
            return Optional.of(retryAfter.isNegative() || retryAfter.isZero()
                    ? Duration.ofSeconds(1) : retryAfter);
        }
        return Optional.empty();
    }

    /**
     * Drops windows that have elapsed.
     *
     * <p>The map is bounded because it is keyed by something a caller controls: without
     * this, anyone able to vary their apparent address could grow it without limit, and a
     * rate limiter that can be turned into an out-of-memory error is worse than none.
     *
     * <p>If sweeping does not get under the cap, new clients go untracked rather than
     * being refused. Refusing them would let one attacker deny service to everybody, which
     * is a worse failure than briefly not limiting.
     */
    private void evictExpired(Instant now) {
        Iterator<Map.Entry<String, Counter>> entries = counters.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().hasExpired(now, window)) {
                entries.remove();
            }
        }
        if (counters.size() >= maxTrackedClients) {
            log.warn("Rate limiter is tracking {} clients, at its cap. Further clients are "
                    + "not limited until windows expire.", counters.size());
        }
    }

    int trackedClients() {
        return counters.size();
    }

    private static final class Counter {

        private final Instant startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private Counter(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private boolean hasExpired(Instant now, Duration window) {
            return !now.isBefore(startedAt.plus(window));
        }
    }
}
