package com.freezhub.notification;

import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationRepository;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers a single notification, in its own transaction (FZ-041).
 *
 * <p>A separate bean from {@link NotificationDispatcher} on purpose. Spring's transactions
 * are proxy-based, so a {@code @Transactional} method called from another method of the
 * *same* bean is not intercepted at all — the batch loop would have run with no
 * transaction per notification and no error to show for it.
 *
 * <p>One transaction per notification also means one failing destination cannot roll back
 * deliveries that already succeeded in the same batch, which is the point of the outbox
 * being per-destination.
 */
@Service
public class NotificationDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);

    private final NotificationRepository notificationRepository;
    private final IntegrationRepository integrationRepository;
    private final ChangeRestrictionRepository changeRestrictionRepository;
    private final Map<IntegrationType, NotificationSender> sendersByType =
            new EnumMap<>(IntegrationType.class);

    public NotificationDelivery(NotificationRepository notificationRepository,
                                IntegrationRepository integrationRepository,
                                ChangeRestrictionRepository changeRestrictionRepository,
                                List<NotificationSender> senders) {
        this.notificationRepository = notificationRepository;
        this.integrationRepository = integrationRepository;
        this.changeRestrictionRepository = changeRestrictionRepository;
        senders.forEach(sender -> sendersByType.put(sender.type(), sender));
    }

    /**
     * Attempts one notification.
     *
     * <p>Re-read inside the transaction rather than trusting a batch snapshot: minutes may
     * pass while earlier notifications in the same batch are delivered.
     *
     * @return true if it was delivered
     */
    @Transactional
    public boolean deliver(Long notificationId, Instant now) {
        Optional<Notification> found = notificationRepository.findById(notificationId);
        if (found.isEmpty()) {
            return false;
        }
        Notification notification = found.get();
        if (notification.getStatus() != NotificationStatus.PENDING) {
            return false;
        }

        Optional<Integration> destination = integrationRepository.findById(notification.getIntegrationId());
        Optional<ChangeRestriction> restriction =
                changeRestrictionRepository.findById(notification.getRestrictionId());

        if (destination.isEmpty() || restriction.isEmpty()) {
            // Both cascade on delete, so this means a concurrent removal. Retrying cannot
            // fix that, so it is abandoned rather than left to burn attempts.
            notification.abandon("Destination or restriction no longer exists");
            return false;
        }

        if (!destination.get().isEnabled()) {
            // Disabled after this was queued. Honour the current intent, but do not spend
            // an attempt on it: the organization may re-enable the destination, and this
            // is not a delivery failure.
            notification.deferUntil("Destination is disabled",
                    RetryPolicy.nextAttemptAfter(0, now));
            return false;
        }

        NotificationSender sender = sendersByType.get(destination.get().getType());
        if (sender == null) {
            // A channel whose adapter is not built yet (FZ-042, FZ-043). Deferred rather
            // than failed: it must deliver once that story lands, and it must not consume
            // attempts in the meantime.
            notification.deferUntil("No sender for channel " + destination.get().getType() + " yet",
                    RetryPolicy.nextAttemptAfter(0, now));
            return false;
        }

        try {
            sender.send(notification, restriction.get(), destination.get());
            notification.markSent();
            return true;
        } catch (RuntimeException failure) {
            notification.markAttemptFailed(failure.getMessage(), now);
            log.warn("Notification {} to {} failed (attempt {}): {}",
                    notification.getId(), destination.get().getType(),
                    notification.getAttempts(), failure.getMessage());
            return false;
        }
    }

}
