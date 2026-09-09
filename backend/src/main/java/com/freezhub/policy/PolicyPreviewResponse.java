package com.freezhub.policy;

import com.freezhub.policy.PolicyEvaluationResponse.MatchedRestriction;
import java.time.Instant;
import java.util.List;

/**
 * The answer to "may I deploy?" when a person asks it from the product (`FZ-120`).
 *
 * <p>Deliberately the same shape as {@link PolicyEvaluationResponse}, minus {@code action}:
 * a person is asking about a deployment, and there is nothing else to ask about. Sharing
 * the record outright was the alternative and it would have meant inventing an action on
 * the caller's behalf so a field could be filled in.
 *
 * <p>Nothing here was written down. A preview leaves no {@code deployment_check} and no
 * audit entry — see {@link PolicyService#preview}.
 */
public record PolicyPreviewResponse(
        PolicyDecision decision,
        String application,
        String environment,
        Instant evaluatedAt,
        String message,
        List<ScopeDimension> unregistered,
        List<MatchedRestriction> restrictions
) {
}
