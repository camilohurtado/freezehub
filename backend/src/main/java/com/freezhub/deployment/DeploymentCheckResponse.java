package com.freezhub.deployment;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.freezhub.policy.PolicyDecision;
import java.time.Instant;

/**
 * One check, as the console shows it.
 *
 * <p>{@code matchedRestrictions} is emitted as raw JSON so a reader gets an array to
 * render rather than an escaped string to unescape.
 */
public record DeploymentCheckResponse(
        Long id,
        String application,
        String environment,
        PolicyDecision decision,
        BlockedReason blockedReason,
        @JsonRawValue String matchedRestrictions,
        String actor,
        String reference,
        String source,
        String checkedBy,
        Instant checkedAt
) {

    static DeploymentCheckResponse from(DeploymentCheck check) {
        return new DeploymentCheckResponse(
                check.getId(),
                check.getApplication(),
                check.getEnvironment(),
                check.getDecision(),
                check.getBlockedReason(),
                check.getMatchedRestrictions(),
                check.getActor(),
                check.getReference(),
                check.getSource(),
                // The credential that asked, not a person — which is why `actor` exists.
                check.getApiKeyLabel(),
                check.getCheckedAt());
    }

}
