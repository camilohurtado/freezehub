package com.freezhub.policy;

import com.freezhub.shared.security.ApiKeyPrincipal;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The machine boundary: "is this deployment currently allowed?" (FZ-051).
 *
 * <p>Authenticated by API key alone — {@code /api/policy/**} is the only path that
 * accepts one, and it accepts nothing else (FZ-052). The organization comes from the
 * credential, so a caller cannot ask about a tenant that is not its own.
 *
 * <p><strong>POST, although it reads nothing but state.</strong> A GET is cacheable, and
 * a cached ALLOW served during a freeze is precisely the failure this endpoint exists to
 * prevent; no proxy should be able to answer it.
 */
@RestController
@RequestMapping("/api/policy")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping("/evaluate")
    public PolicyEvaluationResponse evaluate(@AuthenticationPrincipal ApiKeyPrincipal caller,
                                             @Valid @RequestBody PolicyEvaluationRequest request) {
        return policyService.evaluate(caller.organizationId(), request, Instant.now());
    }

}
