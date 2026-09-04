package com.freezhub.notification;

import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationRepository;
import com.freezhub.subscription.SubscriptionService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the intent to notify (FZ-040).
 *
 * <p>The outbox pattern, and the reason for it: the row is written in the **same
 * transaction** as the domain change it describes. Either the restriction activated and
 * the notification is queued, or neither happened. A process that dies between the two
 * cannot lose the notification, which is exactly what a message broker would have been
 * used for and what 02-architecture.md rules out for the MVP.
 *
 * <p>Hence {@code Propagation.MANDATORY}: calling this outside a transaction is a
 * programming error that would silently reintroduce the gap, so it fails loudly instead.
 *
 * <p>Nothing here sends anything. Delivery is FZ-041 onwards.
 */
@Service
public class NotificationOutbox {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutbox.class);

    private final NotificationRepository notificationRepository;
    private final IntegrationRepository integrationRepository;
    private final SubscriptionService subscriptions;

    public NotificationOutbox(NotificationRepository notificationRepository,
                              IntegrationRepository integrationRepository,
                              SubscriptionService subscriptions) {
        this.notificationRepository = notificationRepository;
        this.integrationRepository = integrationRepository;
        this.subscriptions = subscriptions;
    }

    /**
     * Queues one notification per enabled destination for the organization.
     *
     * <p>Idempotent: a lifecycle reconciliation that runs twice, or a restart mid
     * transition, must not notify anyone twice. The database enforces this with a unique
     * constraint on (restriction, integration, event); this check simply avoids relying on
     * a constraint violation as control flow.
     *
     * <p>An organization with no enabled integrations queues nothing, which is correct —
     * there is nowhere to send.
     *
     * @return how many rows were queued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueue(Long organizationId, Long restrictionId, NotificationEvent event) {
        // A suspended organization stops being announced for (FZ-081). Suppressed at
        // enqueue rather than at delivery, deliberately: skipping it later would leave the
        // row PENDING for ever, growing a backlog that floods the customer with stale
        // announcements the moment they pay - "starting soon" about a freeze that ended
        // three weeks ago. Not queued is not sent, and stays not sent.
        //
        // The reachable cases are all system-driven - lifecycle transitions and the
        // starting-soon sweep - because a suspended organization cannot make the changes
        // that queue the others.
        if (!subscriptions.allowsNotifications(organizationId)) {
            log.debug("Organization {} is not active; not queueing {} for restriction {}",
                    organizationId, event, restrictionId);
            return 0;
        }

        List<Integration> destinations =
                integrationRepository.findAllByOrganizationIdAndEnabledTrue(organizationId);

        int queued = 0;
        for (Integration destination : destinations) {
            boolean alreadyQueued = notificationRepository
                    .existsByRestrictionIdAndIntegrationIdAndEvent(restrictionId, destination.getId(), event);
            if (alreadyQueued) {
                continue;
            }
            notificationRepository.save(
                    new Notification(organizationId, restrictionId, destination.getId(), event));
            queued += 1;
        }

        if (queued > 0) {
            log.debug("Queued {} notification(s) for restriction {} event {}", queued, restrictionId, event);
        }
        return queued;
    }

}
