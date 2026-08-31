package com.freezhub.restriction;

import com.freezhub.notification.NotificationEvent;
import com.freezhub.notification.NotificationOutbox;
import com.freezhub.organization.OrganizationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announces that a freeze is about to begin (FZ-047, fixes {@code OI-3}).
 *
 * <p>The odd one out among lifecycle notifications: every other event is written by the
 * domain change that caused it, but nothing <em>happens</em> when a freeze becomes
 * imminent — time simply passes. So a sweep writes this one.
 *
 * <p>That makes idempotence the whole problem. The sweep runs on an interval, and a
 * restriction stays inside its warning window for the entire lead time, so it is seen
 * again on every pass. Announcing on each would be worse than not announcing at all. The
 * outbox's unique constraint on (restriction, integration, event) is what prevents it —
 * the same mechanism that already makes lifecycle reconciliation safe to re-run.
 *
 * <p>Reconciliation rather than scheduling, for the same reason as
 * {@link RestrictionLifecycleService}: no timers and no in-memory state, so a restart of
 * any length recovers by simply sweeping again.
 */
@Service
public class StartingSoonNotifier {

    private static final Logger log = LoggerFactory.getLogger(StartingSoonNotifier.class);

    private final ChangeRestrictionRepository changeRestrictionRepository;
    private final OrganizationRepository organizationRepository;
    private final NotificationOutbox notificationOutbox;

    public StartingSoonNotifier(ChangeRestrictionRepository changeRestrictionRepository,
                                OrganizationRepository organizationRepository,
                                NotificationOutbox notificationOutbox) {
        this.changeRestrictionRepository = changeRestrictionRepository;
        this.organizationRepository = organizationRepository;
        this.notificationOutbox = notificationOutbox;
    }

    /**
     * Queues a STARTING_SOON announcement for every restriction now inside its
     * organization's warning window.
     *
     * @return how many notifications were queued, which is zero on almost every pass
     */
    @Transactional
    public int announceApproaching(Instant now) {
        Integer maxLeadTime = organizationRepository.maxStartingSoonLeadTimeMinutes();
        if (maxLeadTime == null) {
            return 0;
        }

        // Nothing beyond the largest configured lead time can be due for anyone, so the
        // sweep never has to look at every future restriction.
        Instant horizon = now.plus(Duration.ofMinutes(maxLeadTime));
        List<Object[]> approaching = changeRestrictionRepository.findApproaching(
                now, horizon, RestrictionStatus.CANCELLED);

        int queued = 0;
        for (Object[] row : approaching) {
            ChangeRestriction restriction = (ChangeRestriction) row[0];
            int leadTimeMinutes = (Integer) row[1];

            // Inside the horizon but not necessarily inside *this* organization's window.
            if (restriction.getStartsAt().isAfter(now.plus(Duration.ofMinutes(leadTimeMinutes)))) {
                continue;
            }

            queued += notificationOutbox.enqueue(
                    restriction.getOrganizationId(), restriction.getId(), NotificationEvent.STARTING_SOON);
        }

        if (queued > 0) {
            log.info("Queued {} starting-soon notification(s) at {}", queued, now);
        }
        return queued;
    }

}
