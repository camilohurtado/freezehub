package com.freezhub.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freezhub.ContainersConfig;
import com.freezhub.catalog.Application;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.policy.PolicyDecision;
import com.freezhub.restriction.RestrictionLevel;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The figures the screens show, counted from the checks that produced them (FZ-105). */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class DeploymentCheckSummaryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private DeploymentCheckSummaryService summaries;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private ApplicationRepository applications;

    @Autowired
    private JdbcTemplate jdbc;

    private Long organizationId;

    @BeforeEach
    void setUp() {
        organizationId = organizations
                .saveAndFlush(new Organization("Northwind " + System.nanoTime())).getId();
    }

    /**
     * Written straight to the table rather than through the Policy API, because these tests
     * need checks on chosen days and the API can only record one at the present instant.
     * The columns are the ones {@code 017-deployment-check.yaml} defines.
     */
    private void check(Long owner, PolicyDecision decision, String application,
                       Instant at, String matched) {
        jdbc.update("""
                insert into deployment_check
                  (organization_id, api_key_label, application, environment, decision,
                   matched_restrictions, checked_at)
                values (?, 'ci', ?, 'production', ?, ?, ?)
                """,
                owner, application, decision.name(), matched,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    private void check(PolicyDecision decision, String application, Instant at, String matched) {
        check(organizationId, decision, application, at, matched);
    }

    /**
     * Serialised through the production record, so these fixtures cannot drift into a
     * shape {@code PolicyService} never writes — the aggregate reads this JSON with a
     * native query, and a query proven only against invented JSON proves nothing.
     */
    private String matched(Object... idThenLevel) {
        List<DeploymentCheckRecorder.MatchedRestriction> restrictions = new ArrayList<>();
        for (int i = 0; i < idThenLevel.length; i += 2) {
            Long id = ((Number) idThenLevel[i]).longValue();
            RestrictionLevel level = (RestrictionLevel) idThenLevel[i + 1];
            restrictions.add(new DeploymentCheckRecorder.MatchedRestriction(
                    id, "restriction " + id, level.name()));
        }
        try {
            return MAPPER.writeValueAsString(restrictions);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void countsTodayByDecision() {
        check(PolicyDecision.ALLOW, "payments-api", NOW, null);
        check(PolicyDecision.ALLOW, "checkout-web", NOW, null);
        check(PolicyDecision.BLOCK, "payments-api", NOW, matched(1, RestrictionLevel.HARD_FREEZE));

        var today = summaries.summarise(organizationId, NOW).today();

        assertThat(today.allowed()).isEqualTo(2);
        assertThat(today.refused()).isEqualTo(1);
        assertThat(today.total()).isEqualTo(3);
    }

    @Test
    void yesterdaysChecksAreNotTodays() {
        // Bucketed by UTC date, so "today" has an edge and it has to be the right one.
        check(PolicyDecision.ALLOW, "payments-api", NOW.minus(1, ChronoUnit.DAYS), null);

        var summary = summaries.summarise(organizationId, NOW);

        assertThat(summary.today().total()).isZero();
        assertThat(summary.daily())
                .filteredOn(day -> day.date().equals(LocalDate.of(2026, 9, 14)))
                .singleElement()
                .satisfies(day -> assertThat(day.allowed()).isEqualTo(1));
    }

    @Test
    void theDayBoundaryIsUtcAndNotWhereverTheServerHappensToRun() {
        // 02:00Z on the 15th is still the 14th in the Americas. Bucketing by the server's
        // local day would make two people in different offices disagree about how many
        // deployments were refused yesterday — and every screen says "all times GMT".
        check(PolicyDecision.BLOCK, "payments-api", Instant.parse("2026-09-15T02:00:00Z"),
                matched(1, RestrictionLevel.HARD_FREEZE));
        // 23:00Z on the 14th is already the 15th east of London.
        check(PolicyDecision.ALLOW, "payments-api", Instant.parse("2026-09-14T23:00:00Z"), null);

        var summary = summaries.summarise(organizationId, NOW);

        assertThat(summary.today().refused()).isEqualTo(1);
        assertThat(summary.today().allowed()).isZero();
        assertThat(summary.daily())
                .filteredOn(day -> day.date().equals(LocalDate.of(2026, 9, 14)))
                .singleElement()
                .satisfies(day -> assertThat(day.allowed()).isEqualTo(1));
    }

    @Test
    void theSeriesCoversEveryDayIncludingTheQuietOnes() {
        // A chart that omits quiet days compresses time and makes a gap look like activity.
        check(PolicyDecision.BLOCK, "payments-api", NOW, matched(1, RestrictionLevel.HARD_FREEZE));

        var daily = summaries.summarise(organizationId, NOW).daily();

        assertThat(daily).hasSize(DeploymentCheckSummaryService.DAYS);
        assertThat(daily.getLast().date()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(daily.getFirst().date()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(daily).filteredOn(day -> day.allowed() == 0 && day.refused() == 0)
                .hasSize(DeploymentCheckSummaryService.DAYS - 1);
    }

    @Test
    void applicationsSeenCountsPipelinesNotCatalogEntries() {
        // The gap between the two is the useful figure: services whose pipelines will sail
        // straight through the next freeze.
        applications.saveAndFlush(new Application(organizationId, "payments-api"));
        applications.saveAndFlush(new Application(organizationId, "checkout-web"));
        applications.saveAndFlush(new Application(organizationId, "never-integrated"));

        check(PolicyDecision.ALLOW, "payments-api", NOW, null);
        check(PolicyDecision.ALLOW, "payments-api", NOW, null);
        check(PolicyDecision.BLOCK, "checkout-web", NOW,
                matched(1, RestrictionLevel.HARD_FREEZE));

        var apps = summaries.summarise(organizationId, NOW).applications();

        assertThat(apps.seen()).isEqualTo(2);
        assertThat(apps.total()).isEqualTo(3);
    }

    @Test
    void anApplicationSeenOutsideTheChartWindowStillCounts() {
        // "11 of 14 integrated" answers whether a pipeline is wired up at all, which does
        // not stop being true because the service was quiet for a fortnight.
        applications.saveAndFlush(new Application(organizationId, "payments-api"));
        check(PolicyDecision.ALLOW, "payments-api", NOW.minus(60, ChronoUnit.DAYS), null);

        assertThat(summaries.summarise(organizationId, NOW).applications().seen()).isEqualTo(1);
    }

    @Test
    void anAdvisoryRefusesNothingEvenWhenItMatched() {
        // The rule the mockups validate — "Payments incident #4471 · Advisory · 0". A
        // blocked check lists every restriction that matched, advisories included, but the
        // advisory rode along; it did not refuse. Crediting it would tell an operator an
        // advisory had been stopping deployments.
        check(PolicyDecision.BLOCK, "payments-api", NOW,
                matched(1, RestrictionLevel.HARD_FREEZE, 2, RestrictionLevel.ADVISORY));
        check(PolicyDecision.BLOCK, "payments-api", NOW,
                matched(1, RestrictionLevel.HARD_FREEZE, 2, RestrictionLevel.ADVISORY));

        var refusals = summaries.summarise(organizationId, NOW).refusalsByRestriction();

        assertThat(refusals).singleElement().satisfies(row -> {
            assertThat(row.restrictionId()).isEqualTo(1L);
            assertThat(row.refused()).isEqualTo(2);
        });
    }

    @Test
    void anAllowedCheckRefusesNothing() {
        check(PolicyDecision.ALLOW, "payments-api", NOW, matched(2, RestrictionLevel.ADVISORY));

        assertThat(summaries.summarise(organizationId, NOW).refusalsByRestriction()).isEmpty();
    }

    @Test
    void aCheckThatMatchedNothingIsCountedButCreditedToNoRestriction() {
        // matched_restrictions is null on most rows; the lateral join must not drop the
        // decision counts with them.
        check(PolicyDecision.BLOCK, "payments-api", NOW, null);

        var summary = summaries.summarise(organizationId, NOW);

        assertThat(summary.today().refused()).isEqualTo(1);
        assertThat(summary.refusalsByRestriction()).isEmpty();
    }

    @Test
    void anotherOrganizationsChecksAreNeverCounted() {
        Long other = organizations
                .saveAndFlush(new Organization("Globex " + System.nanoTime())).getId();
        applications.saveAndFlush(new Application(other, "their-app"));
        check(other, PolicyDecision.BLOCK, "their-app", NOW,
                matched(9, RestrictionLevel.HARD_FREEZE));

        var summary = summaries.summarise(organizationId, NOW);

        assertThat(summary.today().total()).isZero();
        assertThat(summary.applications().seen()).isZero();
        assertThat(summary.applications().total()).isZero();
        assertThat(summary.refusalsByRestriction()).isEmpty();
    }

    @Test
    void anOrganizationWithNoChecksGetsZeroesRatherThanNothing() {
        // The dashboard renders these figures unconditionally; a null would be a blank
        // where a nought belongs.
        var summary = summaries.summarise(organizationId, NOW);

        assertThat(summary.today().total()).isZero();
        assertThat(summary.daily()).hasSize(DeploymentCheckSummaryService.DAYS);
        assertThat(summary.daily()).allSatisfy(day -> {
            assertThat(day.allowed()).isZero();
            assertThat(day.refused()).isZero();
        });
        assertThat(summary.applications().seen()).isZero();
        assertThat(summary.refusalsByRestriction()).isEmpty();
    }

    @Test
    void checksOlderThanTheWindowAreOutsideTheSeries() {
        check(PolicyDecision.ALLOW, "payments-api", NOW.minus(20, ChronoUnit.DAYS), null);

        var daily = summaries.summarise(organizationId, NOW).daily();

        assertThat(daily).allSatisfy(day -> assertThat(day.allowed()).isZero());
        assertThat(daily.getFirst().date()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void aNameNobodyCatalogedIsNotCoverage() {
        // Checks record the application name as supplied, so a typo in a pipeline appears
        // here as a name of its own. Counting distinct names rather than catalogued ones
        // reported "5 of 4 applications" against the demo data — a coverage figure larger
        // than the thing it covers.
        applications.saveAndFlush(new Application(organizationId, "payments-api"));

        check(PolicyDecision.ALLOW, "payments-api", NOW, null);
        check(PolicyDecision.ALLOW, "paymnets-api", NOW, null);

        var apps = summaries.summarise(organizationId, NOW).applications();

        assertThat(apps.seen()).isEqualTo(1);
        assertThat(apps.total()).isEqualTo(1);
        assertThat(apps.seen()).isLessThanOrEqualTo(apps.total());
    }
}
