package com.freezhub.deployment;

import com.freezhub.catalog.ApplicationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the deployment check table into the figures the screens show (FZ-105).
 *
 * <p>Read-only, and deliberately built on the table that already exists rather than on a
 * counter maintained alongside it. A counter is a second source of truth that drifts the
 * first time a purge or a backfill runs; counting the rows cannot disagree with the rows.
 *
 * <p>Every day here is a <strong>UTC</strong> day, matching the "all times GMT" the screens
 * print. The bucketing itself happens in SQL, which is the only place it can be pinned —
 * see {@link DeploymentCheckRepository#countByDay}.
 */
@Service
public class DeploymentCheckSummaryService {

    /** A fortnight, because that is the window the checks console draws. */
    static final int DAYS = 14;

    private final DeploymentCheckRepository checks;
    private final ApplicationRepository applications;

    public DeploymentCheckSummaryService(DeploymentCheckRepository checks,
                                         ApplicationRepository applications) {
        this.checks = checks;
        this.applications = applications;
    }

    @Transactional(readOnly = true)
    public DeploymentCheckSummary summarise(Long organizationId, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate from = today.minusDays(DAYS - 1L);

        Map<LocalDate, DeploymentCheckSummary.Day> counted = new HashMap<>();
        for (var row : checks.countByDay(
                organizationId, from.atStartOfDay(ZoneOffset.UTC).toInstant())) {
            counted.put(row.getDay(), new DeploymentCheckSummary.Day(
                    row.getDay(), row.getAllowed(), row.getRefused()));
        }

        // Every day in the window, including the empty ones. A chart that omits quiet days
        // compresses time and makes a gap look like activity.
        List<DeploymentCheckSummary.Day> daily = new ArrayList<>(DAYS);
        for (int i = 0; i < DAYS; i++) {
            LocalDate day = from.plusDays(i);
            daily.add(counted.getOrDefault(day, new DeploymentCheckSummary.Day(day, 0, 0)));
        }

        DeploymentCheckSummary.Day counts = daily.getLast();

        return new DeploymentCheckSummary(
                DeploymentCheckSummary.DecisionCounts.of(counts.allowed(), counts.refused()),
                new DeploymentCheckSummary.Applications(
                        checks.countApplicationsSeen(organizationId),
                        applications.countByOrganizationId(organizationId)),
                daily,
                checks.countRefusalsByRestriction(organizationId).stream()
                        .map(row -> new DeploymentCheckSummary.RestrictionRefusals(
                                row.getRestrictionId(), row.getRefused()))
                        .toList());
    }
}
