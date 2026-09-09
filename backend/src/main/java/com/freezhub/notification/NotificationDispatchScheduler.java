package com.freezhub.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.freezhub.shared.scheduling.SchedulerLock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers outbox draining (FZ-041). Thin, like the lifecycle scheduler — the work lives
 * in {@link NotificationDispatcher} so it can be tested without waiting on a timer.
 *
 * <p>Disabled with {@code freezehub.notifications.enabled=false}, which the tests set so
 * the suite is never racing a background job that marks notifications delivered.
 */
@Component
@ConditionalOnProperty(name = "freezehub.notifications.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationDispatchScheduler {

    /** Comfortably longer than a dispatch sweep, which is bounded by the retry policy. */
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final NotificationDispatcher notificationDispatcher;
    private final SchedulerLock lock;

    public NotificationDispatchScheduler(NotificationDispatcher notificationDispatcher,
                                         SchedulerLock lock) {
        this.notificationDispatcher = notificationDispatcher;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${freezehub.notifications.interval:PT30S}")
    public void dispatch() {
        // Without the lock every pending row is delivered once per instance (FZ-121).
        lock.runIfAcquired("notification-dispatch", LEASE, notificationDispatcher::dispatchPending);
    }

}
