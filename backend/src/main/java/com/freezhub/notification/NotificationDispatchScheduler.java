package com.freezhub.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    private final NotificationDispatcher notificationDispatcher;

    public NotificationDispatchScheduler(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @Scheduled(fixedDelayString = "${freezehub.notifications.interval:PT30S}")
    public void dispatch() {
        notificationDispatcher.dispatchPending();
    }

}
