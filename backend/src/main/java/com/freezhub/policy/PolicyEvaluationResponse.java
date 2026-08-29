package com.freezhub.policy;

import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import java.time.Instant;
import java.util.List;

/**
 * The answer to "may I deploy?" (`04-api.md`).
 *
 * <p>{@code restrictions} lists <em>every</em> restriction that matched, not only the one
 * that decided the outcome: a pipeline told nothing but "BLOCK" cannot act on it, and an
 * {@code ALLOW} carrying advisories is a normal, useful response.
 *
 * <p>{@code message} is always present and is the line worth printing in a build log.
 */
public record PolicyEvaluationResponse(
        PolicyDecision decision,
        PolicyAction action,
        String application,
        String environment,
        Instant evaluatedAt,
        String message,
        List<ScopeDimension> unregistered,
        List<MatchedRestriction> restrictions
) {

    /**
     * A restriction that covers this deployment.
     *
     * <p>{@code reason} is carried because the person reading a blocked pipeline needs to
     * know why the freeze exists, not merely that it does. Scope is deliberately absent:
     * the caller does not need to re-derive a match FreezeHub has already made, and
     * publishing it would invite a second implementation of the matching rules outside
     * FreezeHub — the same reasoning that keeps scope out of webhook payloads (FZ-043).
     */
    public record MatchedRestriction(
            Long id,
            String name,
            String reason,
            RestrictionLevel level,
            Instant startsAt,
            Instant endsAt
    ) {

        static MatchedRestriction from(ChangeRestriction restriction) {
            return new MatchedRestriction(
                    restriction.getId(),
                    restriction.getName(),
                    restriction.getReason(),
                    restriction.getLevel(),
                    restriction.getStartsAt(),
                    restriction.getEndsAt());
        }
    }

}
