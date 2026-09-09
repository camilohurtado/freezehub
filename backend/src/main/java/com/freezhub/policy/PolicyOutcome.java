package com.freezhub.policy;

import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import java.util.List;

/**
 * What the rules say about one deployment, before anything is written down (`FZ-120`).
 *
 * <p>Separated from {@link PolicyService#evaluate} so a person can ask the same question a
 * pipeline asks and get the same answer from the same code. The alternative — a second
 * implementation of the matching rules for the human path — would be a second source of
 * truth for the one thing this product exists to decide, and the two would disagree the
 * first time either changed.
 *
 * <p>Carries no side effects and describes none: the audit entry, the metric and the
 * recorded check all belong to the caller that wanted them.
 */
public record PolicyOutcome(
        /** Dimensions naming something this organization does not have. Empty is the normal case. */
        List<ScopeDimension> unregistered,
        /** Every restriction in force that covers this deployment, advisories included. */
        List<ChangeRestriction> matched
) {

    boolean isUnregistered() {
        return !unregistered.isEmpty();
    }

    /**
     * Whether the deployment is refused.
     *
     * <p>An unrecognised name blocks (`FZ-051`), and otherwise only a hard freeze does —
     * an advisory matched and reported, which is the whole difference between the levels.
     */
    public boolean blocked() {
        return isUnregistered()
                || matched.stream().anyMatch(r -> r.getLevel() == RestrictionLevel.HARD_FREEZE);
    }
}
