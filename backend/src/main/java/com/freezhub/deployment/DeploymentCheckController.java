package com.freezhub.deployment;

import com.freezhub.policy.PolicyDecision;
import com.freezhub.policy.PolicyPreviewResponse;
import com.freezhub.policy.PolicyService;
import com.freezhub.shared.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final DeploymentCheckSummaryService summaries;
    private final PolicyService policyService;

    public DeploymentCheckController(DeploymentCheckRepository deploymentCheckRepository,
                                     DeploymentCheckSummaryService summaries,
                                     PolicyService policyService) {
        this.deploymentCheckRepository = deploymentCheckRepository;
        this.summaries = summaries;
        this.policyService = policyService;
    }

    /**
     * What the checks add up to (FZ-105).
     *
     * <p>Open to any member, like the list beside it: a team looking at whether their own
     * deployment got through has the same reason to see how often anything is refused.
     *
     * <p>One response rather than four endpoints because the dashboard draws three of
     * these figures in a single row, and four round trips to paint one row is four
     * chances for it to paint inconsistently.
     */
    @GetMapping("/summary")
    public DeploymentCheckSummary summary(@AuthenticationPrincipal AuthenticatedUser caller) {
        return summaries.summarise(caller.organizationId(), Instant.now());
    }

    /**
     * "Can I deploy?", asked by a person (FZ-120).
     *
     * <p>Here rather than under {@code /api/policy}, which is bound to the API-key chain
     * and accepts no human credential at all (FZ-052) — a signed-in person cannot reach it,
     * and widening that chain to let them would put the deployment gate behind two kinds
     * of credential.
     *
     * <p><strong>A GET, unlike the machine endpoint's POST.</strong> The POST is a POST
     * because a cached ALLOW served during a freeze is the failure that endpoint exists to
     * prevent. Nothing enforces anything on this answer, and a GET cannot record — which
     * is exactly the property this story claims: asking here leaves no trace in the checks
     * console, so "every time a pipeline asked" stays true of it.
     */
    @GetMapping("/preview")
    public PolicyPreviewResponse preview(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @RequestParam String application,
                                         @RequestParam String environment) {
        if (application.isBlank() || environment.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Both application and environment are required.");
        }

        return policyService.preview(caller.organizationId(), application, environment,
                Instant.now());
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
