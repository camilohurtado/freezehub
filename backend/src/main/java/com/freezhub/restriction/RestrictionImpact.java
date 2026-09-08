package com.freezhub.restriction;

/**
 * What a restriction actually did (`1d`'s "What it has done", FZ-112).
 *
 * <p>The three figures that make a completed freeze more than a row in a list. Each is
 * counted from the records the restriction produced rather than stored alongside it, so
 * they cannot drift from what happened.
 */
public record RestrictionImpact(
        /**
         * Deployments this restriction refused.
         *
         * <p>Only counted where it was a hard freeze at check time: a blocked check names
         * every restriction that matched, advisories included, but an advisory rode along
         * rather than refusing anything (`FZ-105`).
         */
        long checksRefused,
        /** Distinct applications among those refusals — how far the freeze actually reached. */
        long pipelinesAffected,
        /** Lifecycle announcements sent about it, and how many did not arrive. */
        long notificationsSent,
        long notificationsFailed
) {
}
