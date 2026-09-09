package com.freezhub.deployment;

import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.shared.scheduling.SchedulerLock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes deployment checks past their organization's retention (FZ-070).
 *
 * <p>Per organization rather than one sweep over the table, because the window is per
 * organization: a customer keeping five years must not be truncated to another's ninety
 * days.
 *
 * <p>This is the only thing bounding a table written once per deployment, and the only
 * thing bounding how long customer PII — the deploying engineer's identity — is held. It
 * is not an optimisation.
 *
 * <p>Reconciliation, like the lifecycle: it holds no state, so a run missed during an
 * outage of any length is corrected by simply running again.
 */
@Service
@ConditionalOnProperty(name = "freezehub.deployment-checks.retention.enabled",
        havingValue = "true", matchIfMissing = true)
public class DeploymentCheckRetention {

    private static final Logger log = LoggerFactory.getLogger(DeploymentCheckRetention.class);

    private static final Duration LEASE = Duration.ofMinutes(30);

    private final DeploymentCheckRepository deploymentCheckRepository;
    private final OrganizationRepository organizationRepository;
    private final SchedulerLock lock;

    public DeploymentCheckRetention(DeploymentCheckRepository deploymentCheckRepository,
                                    OrganizationRepository organizationRepository,
                                    SchedulerLock lock) {
        this.deploymentCheckRepository = deploymentCheckRepository;
        this.organizationRepository = organizationRepository;
        this.lock = lock;
    }

    /**
     * Daily, and deliberately not more often: nothing depends on a record disappearing
     * promptly, and a delete sweep competing with the write path once per deployment is
     * worth avoiding.
     *
     * <p><strong>{@code @Transactional} belongs here, not only on {@link #purge}.</strong>
     * This method calls that one on {@code this}, and {@code @Transactional} is applied by
     * a proxy — a self-invocation never reaches it. Without the annotation on the method
     * the scheduler actually calls, no transaction starts and the {@code @Modifying}
     * delete throws {@code TransactionRequiredException}. It did, once a day, from
     * {@code FZ-070} until {@code FZ-114}, while the tests passed: they called
     * {@code purge} on the injected bean, which does go through the proxy.
     */
    @Scheduled(fixedDelayString = "${freezehub.deployment-checks.retention.interval:PT24H}",
            initialDelayString = "PT5M")
    @Transactional
    public void purgeScheduled() {
        // Harmless to duplicate — a delete is idempotent — but locked with the rest so
        // there is one answer to "is this job coordinated?" rather than five (FZ-121).
        lock.runIfAcquired("deployment-check-retention", LEASE, () -> purge(Instant.now()));
    }

    /** @return how many records were removed */
    @Transactional
    public int purge(Instant now) {
        int removed = 0;

        for (Organization organization : organizationRepository.findAll()) {
            Instant before = now.minus(Duration.ofDays(organization.getDeploymentCheckRetentionDays()));
            removed += deploymentCheckRepository.deleteExpired(organization.getId(), before);
        }

        if (removed > 0) {
            log.info("Purged {} deployment check(s) past retention at {}", removed, now);
        }
        return removed;
    }

}
