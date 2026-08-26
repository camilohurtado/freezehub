package com.freezhub.restriction;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Lifecycle reconciliation (FZ-025), driven by an explicit instant rather than by waiting
 * on the scheduler - the transitions are then deterministic and fast to verify.
 *
 * <p>Every restriction here is positioned relative to real "now" so that reconciling
 * cannot disturb the future-dated rows other test classes leave in the shared container.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class RestrictionLifecycleTest {

    @Autowired
    private RestrictionLifecycleService restrictionLifecycleService;

    @Autowired
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long newCreator() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        return userRepository.saveAndFlush(new User(
                organization.getId(), subject, subject + "@acme.test", UserRole.MEMBER)).getId();
    }

    /** Persists a restriction with the given window and status, bypassing create validation. */
    private ChangeRestriction given(Instant startsAt, Instant endsAt, RestrictionStatus status) {
        Long creator = newCreator();
        Long organizationId = userRepository.findById(creator).orElseThrow().getOrganizationId();

        ChangeRestriction restriction = changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                organizationId, "Freeze " + System.nanoTime(), null, "Reason",
                RestrictionLevel.HARD_FREEZE, startsAt, endsAt, creator,
                Set.of(), Set.of(), Set.of()));

        if (status != RestrictionStatus.SCHEDULED) {
            jdbcTemplate.update("UPDATE change_restriction SET status = ? WHERE id = ?",
                    status.name(), restriction.getId());
        }
        return restriction;
    }

    private RestrictionStatus statusOf(ChangeRestriction restriction) {
        return RestrictionStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM change_restriction WHERE id = ?", String.class, restriction.getId()));
    }

    private Instant updatedAtOf(ChangeRestriction restriction) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM change_restriction WHERE id = ?",
                java.sql.Timestamp.class, restriction.getId()).toInstant();
    }

    @Test
    void activatesARestrictionWhoseStartTimeHasArrived() {
        Instant now = Instant.now();
        ChangeRestriction restriction = given(
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);

        restrictionLifecycleService.reconcile(now);

        assertThat(statusOf(restriction)).isEqualTo(RestrictionStatus.ACTIVE);
    }

    @Test
    void leavesARestrictionThatHasNotStartedYet() {
        Instant now = Instant.now();
        ChangeRestriction restriction = given(
                now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);

        restrictionLifecycleService.reconcile(now);

        assertThat(statusOf(restriction)).isEqualTo(RestrictionStatus.SCHEDULED);
    }

    @Test
    void completesAnActiveRestrictionWhoseEndTimeHasPassed() {
        Instant now = Instant.now();
        ChangeRestriction restriction = given(
                now.minus(3, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), RestrictionStatus.ACTIVE);

        restrictionLifecycleService.reconcile(now);

        assertThat(statusOf(restriction)).isEqualTo(RestrictionStatus.COMPLETED);
    }

    @Test
    void completesARestrictionWhoseWholeWindowElapsedWhileTheApplicationWasDown() {
        // The restart case: the process missed the entire window, so the restriction never
        // got marked ACTIVE. Leaving it SCHEDULED for ever would be wrong - it elapsed.
        Instant now = Instant.now();
        ChangeRestriction restriction = given(
                now.minus(5, ChronoUnit.HOURS), now.minus(4, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);

        restrictionLifecycleService.reconcile(now);

        assertThat(statusOf(restriction)).isEqualTo(RestrictionStatus.COMPLETED);
    }

    @Test
    void neverActivatesACancelledRestriction() {
        // The headline requirement: "ensure cancellation prevents future activation".
        Instant now = Instant.now();
        ChangeRestriction restriction = given(
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), RestrictionStatus.CANCELLED);

        restrictionLifecycleService.reconcile(now);

        assertThat(statusOf(restriction)).isEqualTo(RestrictionStatus.CANCELLED);
    }

    @Test
    void neverCompletesACancelledRestriction() {
        // Cancellation is terminal in both directions: a cancelled restriction whose window
        // has passed stays CANCELLED rather than being reported as COMPLETED.
        Instant now = Instant.now();
        ChangeRestriction restriction = given(
                now.minus(3, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), RestrictionStatus.CANCELLED);

        restrictionLifecycleService.reconcile(now);

        assertThat(statusOf(restriction)).isEqualTo(RestrictionStatus.CANCELLED);
    }

    @Test
    void leavesAnAlreadyCompletedRestrictionAlone() {
        Instant now = Instant.now();
        ChangeRestriction restriction = given(
                now.minus(3, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), RestrictionStatus.COMPLETED);

        restrictionLifecycleService.reconcile(now);

        assertThat(statusOf(restriction)).isEqualTo(RestrictionStatus.COMPLETED);
    }

    @Test
    void isIdempotent() {
        // Reconciliation holds no state, so a second pass over settled data changes nothing.
        Instant now = Instant.now();
        ChangeRestriction activating = given(
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);
        ChangeRestriction completing = given(
                now.minus(3, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), RestrictionStatus.ACTIVE);

        restrictionLifecycleService.reconcile(now);
        assertThat(statusOf(activating)).isEqualTo(RestrictionStatus.ACTIVE);
        assertThat(statusOf(completing)).isEqualTo(RestrictionStatus.COMPLETED);

        RestrictionLifecycleService.LifecycleReconciliation second =
                restrictionLifecycleService.reconcile(now);

        assertThat(second.changedAnything()).isFalse();
        assertThat(statusOf(activating)).isEqualTo(RestrictionStatus.ACTIVE);
        assertThat(statusOf(completing)).isEqualTo(RestrictionStatus.COMPLETED);
    }

    @Test
    void reconcilesAcrossOrganizations() {
        // A system process, not a tenant-scoped one: every organization's restrictions
        // must transition, not only the caller's.
        Instant now = Instant.now();
        ChangeRestriction firstOrg = given(
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);
        ChangeRestriction secondOrg = given(
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);

        assertThat(firstOrg.getOrganizationId()).isNotEqualTo(secondOrg.getOrganizationId());

        restrictionLifecycleService.reconcile(now);

        assertThat(statusOf(firstOrg)).isEqualTo(RestrictionStatus.ACTIVE);
        assertThat(statusOf(secondOrg)).isEqualTo(RestrictionStatus.ACTIVE);
    }

    @Test
    void stampsUpdatedAtOnTransition() {
        // A bulk JPQL update bypasses @PreUpdate, so updatedAt is set explicitly. Without
        // that the row would silently keep a stale timestamp.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ChangeRestriction restriction = given(
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);
        Instant before = updatedAtOf(restriction);

        Instant reconciledAt = now.plus(1, ChronoUnit.MINUTES);
        restrictionLifecycleService.reconcile(reconciledAt);

        assertThat(updatedAtOf(restriction)).isAfter(before);
    }

    @Test
    void reportsWhatItChanged() {
        Instant now = Instant.now();
        given(now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);
        given(now.minus(3, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), RestrictionStatus.ACTIVE);

        RestrictionLifecycleService.LifecycleReconciliation result =
                restrictionLifecycleService.reconcile(now);

        // Other test classes share the container, so assert "at least", not exact totals.
        assertThat(result.activated()).isGreaterThanOrEqualTo(1);
        assertThat(result.completed()).isGreaterThanOrEqualTo(1);
        assertThat(result.changedAnything()).isTrue();
    }

}
