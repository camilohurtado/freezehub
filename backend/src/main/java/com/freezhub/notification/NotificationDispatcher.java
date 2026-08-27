package com.freezhub.notification;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Drains the outbox (FZ-041).
 *
 * <p>Only the loop. Each notification is delivered by {@link NotificationDelivery}, a
 * separate bean so its per-notification transaction is actually applied — a
 * {@code @Transactional} method invoked from within the same bean is not intercepted by
 * Spring's proxy at all.
 *
 * <p>Channel-agnostic: senders are resolved by destination type, so email (FZ-042) and
 * webhook (FZ-043) arrive as new senders rather than changes here.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    /** Bounded so one pass cannot monopolise the scheduler or the connection pool. */
    private static final int BATCH_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final NotificationDelivery notificationDelivery;

    public NotificationDispatcher(NotificationRepository notificationRepository,
                                  NotificationDelivery notificationDelivery) {
        this.notificationRepository = notificationRepository;
        this.notificationDelivery = notificationDelivery;
    }

    public DispatchResult dispatchPending() {
        return dispatchPending(Instant.now());
    }

    /** Explicit clock so retry scheduling can be tested without waiting for real time. */
    public DispatchResult dispatchPending(Instant now) {
        List<Notification> pending =
                notificationRepository.findAllByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                        NotificationStatus.PENDING, now, Limit.of(BATCH_SIZE));

        int sent = 0;
        int failed = 0;
        for (Notification notification : pending) {
            if (notificationDelivery.deliver(notification.getId(), now)) {
                sent += 1;
            } else {
                failed += 1;
            }
        }

        if (sent > 0 || failed > 0) {
            log.info("Notification dispatch: {} sent, {} failed", sent, failed);
        }
        return new DispatchResult(sent, failed);
    }

    public record DispatchResult(int sent, int failed) {
    }

}
