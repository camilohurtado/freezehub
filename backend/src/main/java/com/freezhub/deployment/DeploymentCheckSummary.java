package com.freezhub.deployment;

import java.time.LocalDate;
import java.util.List;

/**
 * What the deployment checks add up to (FZ-105).
 *
 * <p>Four figures the screens show and nothing produced. They are served together, in one
 * response, because they are read together: the dashboard renders three of them side by
 * side and the checks console renders the fourth above its list. Splitting them into four
 * endpoints would mean four round trips to draw one row.
 *
 * <p>Everything here is derived from {@code deployment_check}, which has recorded every
 * evaluation since {@code FZ-070}. No new writes, no new table, and the retention setting
 * still bounds all of it — an organization keeping ninety days gets ninety days of chart.
 */
public record DeploymentCheckSummary(
        DecisionCounts today,
        Applications applications,
        List<Day> daily,
        List<RestrictionRefusals> refusalsByRestriction,
        /**
         * Checks refused across the same window as {@code daily} because a name was not
         * recognised — a different problem from a freeze, and a different fix.
         */
        long unregistered
) {

    /**
     * A day's checks, split by what they were told.
     *
     * <p>{@code refused} rather than "blocked": the console calls these checks, not
     * deployments ({@code D-19}), and what FreezeHub did was refuse a question. Whether a
     * deployment then happened anyway is something it is never told.
     */
    public record DecisionCounts(long total, long allowed, long refused) {

        static DecisionCounts of(long allowed, long refused) {
            return new DecisionCounts(allowed + refused, allowed, refused);
        }
    }

    /**
     * How much of the catalog is actually wired up.
     *
     * <p>{@code seen} counts applications that have asked at least once, not applications
     * registered. The gap between the two is the useful part: it is the list of services
     * whose pipelines will sail straight through the next freeze.
     */
    public record Applications(long seen, long total) {
    }

    /** One UTC day of the series. Days with no checks are present with zeroes. */
    public record Day(LocalDate date, long allowed, long refused) {
    }

    /** One restriction and the deployments it actually refused. */
    public record RestrictionRefusals(Long restrictionId, long refused) {
    }
}
