package com.freezhub.restriction;

import com.freezhub.deployment.DeploymentCheckRepository;
import com.freezhub.notification.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What one restriction did, counted from the records it produced (FZ-112).
 *
 * <p>A separate read from the restriction itself, and a separate endpoint: the detail
 * response is the domain object, and these are counts <em>about</em> it drawn from two
 * other tables. Folding them in would make every read of a restriction — including the
 * dashboard's, which fetches the in-force ones — pay for aggregates it does not show.
 */
@Service
public class RestrictionImpactService {

    private final DeploymentCheckRepository checks;
    private final NotificationRepository notifications;

    public RestrictionImpactService(DeploymentCheckRepository checks,
                                    NotificationRepository notifications) {
        this.checks = checks;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public RestrictionImpact of(Long organizationId, Long restrictionId) {
        var refusals = checks.countRefusalsForRestriction(organizationId, restrictionId);
        return new RestrictionImpact(
                refusals == null ? 0 : refusals.getRefused(),
                refusals == null ? 0 : refusals.getApplications(),
                notifications.countByOrganizationIdAndRestrictionId(organizationId, restrictionId),
                notifications.countByOrganizationIdAndRestrictionIdAndStatus(
                        organizationId, restrictionId,
                        com.freezhub.notification.NotificationStatus.FAILED));
    }
}
