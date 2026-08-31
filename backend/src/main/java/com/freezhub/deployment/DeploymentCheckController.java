package com.freezhub.deployment;

import com.freezhub.policy.PolicyDecision;
import com.freezhub.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What has been asked of the deployment gate, and what it answered (FZ-070).
 *
 * <p><strong>Any member, not administrators only</strong> — unlike the audit trail. This
 * is the screen a team looks at to see whether their own deployment got through, and
 * making them ask an administrator would defeat the point. It reveals nothing an engineer
 * could not learn by running the check themselves.
 */
@RestController
@RequestMapping("/api/deployment-checks")
public class DeploymentCheckController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final DeploymentCheckRepository deploymentCheckRepository;

    public DeploymentCheckController(DeploymentCheckRepository deploymentCheckRepository) {
        this.deploymentCheckRepository = deploymentCheckRepository;
    }

    /**
     * Newest first. {@code beforeId} is a cursor rather than an offset: this table is
     * written once per deployment, so rows arriving between two page requests would shift
     * an offset window and silently skip entries.
     *
     * <p>{@code decision=BLOCK} is the view worth having — every attempt that was refused,
     * which is the question nobody could answer before this existed.
     */
    @GetMapping
    public List<DeploymentCheckResponse> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                              @RequestParam(required = false) Long beforeId,
                                              @RequestParam(required = false) String application,
                                              @RequestParam(required = false) String environment,
                                              @RequestParam(required = false) PolicyDecision decision,
                                              @RequestParam(required = false) Integer limit) {
        return deploymentCheckRepository
                .findPage(caller.organizationId(), beforeId, application, environment, decision,
                        Limit.of(capped(limit)))
                .stream()
                .map(DeploymentCheckResponse::from)
                .toList();
    }

    private int capped(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
    }

}
