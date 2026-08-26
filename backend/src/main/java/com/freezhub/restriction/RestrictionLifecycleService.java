package com.freezhub.restriction;

import com.freezhub.notification.NotificationEvent;
import com.freezhub.notification.NotificationOutbox;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves restrictions through {@code SCHEDULED -> ACTIVE -> COMPLETED} (FZ-025).
 *
 * <p>Reconciliation, not scheduling: this holds no timers and no in-memory state. Each run
 * compares the persisted timestamps against a supplied instant and corrects the stored
 * status, so it is idempotent, safe to run concurrently with itself, and recovers by
 * itself after a restart or an outage of any length (02-architecture.md: "the persisted
 * timestamps/status are authoritative").
 *
 * <p>The trigger lives in {@link RestrictionLifecycleScheduler}. Keeping it separate means
 * the transitions can be tested against an explicit clock rather than by waiting.
 *
 * <p>Cancellation is honoured implicitly: both queries filter on status, and CANCELLED
 * matches neither, so a cancelled restriction can never be activated or completed.
 */
@Service
public class RestrictionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RestrictionLifecycleService.class);

    /** Statuses a restriction can still be completed from - CANCELLED is terminal. */
    private static final List<RestrictionStatus> OPEN_STATUSES =
            List.of(RestrictionStatus.SCHEDULED, RestrictionStatus.ACTIVE);

    private final ChangeRestrictionRepository changeRestrictionRepository;
    private final NotificationOutbox notificationOutbox;

    public RestrictionLifecycleService(ChangeRestrictionRepository changeRestrictionRepository,
                                       NotificationOutbox notificationOutbox) {
        this.changeRestrictionRepository = changeRestrictionRepository;
        this.notificationOutbox = notificationOutbox;
    }

    /**
     * Brings every restriction's stored status into line with {@code now}.
     *
     * <p>Deliberately not tenant-scoped: this is a system process reconciling the whole
     * table, not a user action.
     */
    @Transactional
    public LifecycleReconciliation reconcile(Instant now) {
        // Captured before the updates: a set-based UPDATE reports a count, not the rows,
        // and each restriction that actually transitions has to be announced (FZ-040).
        // The two predicates are disjoint — activation excludes an already-elapsed window.
        List<ChangeRestriction> activating =
                changeRestrictionRepository.findDueForActivation(now, RestrictionStatus.SCHEDULED);
        List<ChangeRestriction> completing =
                changeRestrictionRepository.findDueForCompletion(now, OPEN_STATUSES);

        int activated = changeRestrictionRepository.activateDue(
                now, RestrictionStatus.SCHEDULED, RestrictionStatus.ACTIVE);
        int completed = changeRestrictionRepository.completeDue(
                now, OPEN_STATUSES, RestrictionStatus.COMPLETED);

        // Still inside the same transaction as the status change itself, so a crash cannot
        // leave a restriction activated with nobody ever told.
        activating.forEach(restriction -> notificationOutbox.enqueue(
                restriction.getOrganizationId(), restriction.getId(), NotificationEvent.ACTIVATED));
        completing.forEach(restriction -> notificationOutbox.enqueue(
                restriction.getOrganizationId(), restriction.getId(), NotificationEvent.COMPLETED));

        if (activated > 0 || completed > 0) {
            log.info("Restriction lifecycle reconciled at {}: {} activated, {} completed",
                    now, activated, completed);
        }

        return new LifecycleReconciliation(activated, completed);
    }

    public record LifecycleReconciliation(int activated, int completed) {

        public boolean changedAnything() {
            return activated > 0 || completed > 0;
        }
    }

}
