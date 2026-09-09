package com.freezhub.restriction;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.freezhub.shared.scheduling.SchedulerLock;
import java.time.Duration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers lifecycle reconciliation (FZ-025). Deliberately thin - all the logic lives in
 * {@link RestrictionLifecycleService} so it can be tested against an explicit clock.
 *
 * <p>Two triggers, because a periodic tick alone is not enough to satisfy
 * "lifecycle correctness must survive application restarts":
 *
 * <ul>
 *   <li><b>On startup</b> - after a restart or a deployment the stored statuses may be
 *       stale by however long the process was down. Reconciling once on
 *       {@code ApplicationReadyEvent} closes that gap immediately instead of leaving it
 *       open until the first tick.</li>
 *   <li><b>Periodically</b> - to pick up restrictions that come due while running.</li>
 * </ul>
 *
 * <p>Disabled with {@code freezehub.lifecycle.enabled=false}, which the tests use so the
 * suite is not racing a background job.
 */
@Component
@ConditionalOnProperty(name = "freezehub.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class RestrictionLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(RestrictionLifecycleScheduler.class);

    private static final Duration LEASE = Duration.ofMinutes(5);

    private final RestrictionLifecycleService restrictionLifecycleService;
    private final StartingSoonNotifier startingSoonNotifier;
    private final SchedulerLock lock;

    public RestrictionLifecycleScheduler(RestrictionLifecycleService restrictionLifecycleService,
                                         StartingSoonNotifier startingSoonNotifier,
                                         SchedulerLock lock) {
        this.restrictionLifecycleService = restrictionLifecycleService;
        this.startingSoonNotifier = startingSoonNotifier;
        this.lock = lock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        log.info("Reconciling restriction lifecycle on startup");
        // Two instances booting together is precisely when this collides (FZ-121).
        lock.runIfAcquired("restriction-lifecycle", LEASE, this::sweep);
    }

    @Scheduled(fixedDelayString = "${freezehub.lifecycle.interval:PT1M}")
    public void reconcilePeriodically() {
        /*
         * The status update is idempotent — its WHERE clause saves it — but the outbox
         * and audit writes that follow are not. Unlocked, two instances announce one
         * freeze twice and record two activations of it (FZ-121).
         */
        lock.runIfAcquired("restriction-lifecycle", LEASE, this::sweep);
    }

    /**
     * Both time-driven sweeps, on the same tick.
     *
     * <p>Status reconciliation first: it is what makes a restriction that has already
     * begun stop being a candidate for "starting soon" (FZ-047).
     */
    private void sweep() {
        Instant now = Instant.now();
        restrictionLifecycleService.reconcile(now);
        startingSoonNotifier.announceApproaching(now);
    }

}
