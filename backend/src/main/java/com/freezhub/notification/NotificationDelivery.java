package com.freezhub.notification;

import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationRepository;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
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
    public boolean deliver(Long notificationId) {
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
            // Both cascade on delete, so this means a concurrent removal: nothing to send.
            notification.markAttemptFailed("Destination or restriction no longer exists");
            return false;
        }

        if (!destination.get().isEnabled()) {
            // Disabled after this was queued. Honour the current intent rather than
            // announcing to a destination the organization has switched off.
            notification.markAttemptFailed("Destination is disabled");
            return false;
        }

        NotificationSender sender = sendersByType.get(destination.get().getType());
        if (sender == null) {
            // A channel whose adapter is not built yet (FZ-042, FZ-043). Left PENDING so it
            // delivers when that story lands rather than being lost.
            notification.markAttemptFailed("No sender for channel " + destination.get().getType() + " yet");
            return false;
        }

        try {
            sender.send(notification, restriction.get(), destination.get());
            notification.markSent();
            return true;
        } catch (RuntimeException failure) {
            notification.markAttemptFailed(failure.getMessage());
            log.warn("Notification {} to {} failed (attempt {}): {}",
                    notification.getId(), destination.get().getType(),
                    notification.getAttempts(), failure.getMessage());
            return false;
        }
    }

}
