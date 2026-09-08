package com.freezhub.notification;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends an announcement again after it was given up on (`FZ-119`).
 *
 * <p>Nothing here delivers anything. The outbox already knows how — this puts an
 * exhausted row back in front of the dispatcher, which picks it up on its next pass.
 * A second sender would be a second thing to keep correct.
 */
@Service
public class NotificationRetryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryService.class);

    private final NotificationRepository notifications;

    public NotificationRetryService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * Requeues the failed deliveries of one event.
     *
     * <p><strong>Only the failed ones.</strong> Re-sending an event wholesale would
     * announce a freeze a second time to every channel that already accepted it — turning
     * a fix for the one person who was not told into a duplicate for everyone who was.
     *
     * <p>The attempt count resets, and it has to: {@code RetryPolicy} calls a row
     * exhausted at six, so a requeue that kept the count would be given up on again
     * without a single new attempt.
     *
     * @return how many deliveries were requeued; zero when nothing had failed
     */
    @Transactional
    public int retry(Long organizationId, Long restrictionId, NotificationEvent event, Instant now) {
        List<Notification> failed = notifications
                .findAllByOrganizationIdAndRestrictionIdAndEventAndStatus(
                        organizationId, restrictionId, event, NotificationStatus.FAILED);

        for (Notification notification : failed) {
            notification.requeue(now);
        }
        notifications.saveAll(failed);

        if (!failed.isEmpty()) {
            log.info("Requeued {} failed delivery(ies) of {} for restriction {}",
                    failed.size(), event, restrictionId);
        }
        return failed.size();
    }
}
