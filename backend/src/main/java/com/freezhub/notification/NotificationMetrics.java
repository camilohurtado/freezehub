package com.freezhub.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What is happening to announcements (FZ-062).
 *
 * <p>The failure this exists for is a quiet one: a freeze is announced, the delivery
 * fails every time, and nobody notices until an engineer deploys during a freeze they
 * were never told about. Retry state is already visible per row (`FZ-044`), but nothing
 * showed the shape of it — that deliveries are failing at all, or that some have been
 * abandoned.
 *
 * <p>Deliberately <strong>not</strong> a health indicator. The load balancer reads health,
 * so reporting DOWN because notifications are failing would have ECS replace the tasks and
 * take the API down over a problem the API does not have.
 */
@Component
public class NotificationMetrics {

    private final Counter sent;
    private final Counter failed;
    private final Counter abandoned;
    private final Counter deferred;

    public NotificationMetrics(MeterRegistry registry) {
        this.sent = counter(registry, "sent", "Delivered to the destination");
        this.failed = counter(registry, "failed", "A delivery attempt failed; it will be retried");
        this.abandoned = counter(registry, "abandoned",
                "Given up on — the retry limit was reached, or the destination is gone");
        this.deferred = counter(registry, "deferred",
                "Skipped without spending an attempt — the destination is disabled or unconfigured");
    }

    private Counter counter(MeterRegistry registry, String outcome, String description) {
        return Counter.builder("freezehub.notifications")
                .tag("outcome", outcome)
                .description(description)
                .register(registry);
    }

    public void sent() {
        sent.increment();
    }

    public void failed() {
        failed.increment();
    }

    /** The one worth alerting on: an announcement that will now never arrive. */
    public void abandoned() {
        abandoned.increment();
    }

    public void deferred() {
        deferred.increment();
    }

}
