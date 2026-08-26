package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationRepository;
import com.freezhub.integration.IntegrationType;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.restriction.RestrictionLifecycleService;
import com.freezhub.restriction.RestrictionStatus;
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
 * Lifecycle transitions must announce themselves (FZ-040). The reconciler uses set-based
 * updates that report a count rather than rows, so this checks the notifications are
 * actually queued for the restrictions that moved — and only those.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class LifecycleNotificationTest {

    @Autowired
    private RestrictionLifecycleService restrictionLifecycleService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private IntegrationRepository integrationRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Fixture(Long organizationId, Long userId) {
    }

    private Fixture givenOrganizationWithSlack() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        User user = userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.MEMBER));
        integrationRepository.saveAndFlush(
                new Integration(organization.getId(), IntegrationType.SLACK, "{}"));
        return new Fixture(organization.getId(), user.getId());
    }

    private ChangeRestriction given(Fixture fixture, Instant startsAt, Instant endsAt,
                                    RestrictionStatus status) {
        ChangeRestriction restriction = changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                fixture.organizationId(), "Freeze " + System.nanoTime(), null, "Reason",
                RestrictionLevel.HARD_FREEZE, startsAt, endsAt, fixture.userId(),
                Set.of(), Set.of(), Set.of()));

        if (status != RestrictionStatus.SCHEDULED) {
            jdbcTemplate.update("UPDATE change_restriction SET status = ? WHERE id = ?",
                    status.name(), restriction.getId());
        }
        return restriction;
    }

    private Iterable<NotificationEvent> eventsFor(ChangeRestriction restriction) {
        return notificationRepository.findAllByRestrictionIdOrderByIdAsc(restriction.getId()).stream()
                .map(Notification::getEvent)
                .toList();
    }

    @Test
    void announcesAnActivation() {
        Fixture fixture = givenOrganizationWithSlack();
        Instant now = Instant.now();
        ChangeRestriction restriction = given(fixture,
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);

        restrictionLifecycleService.reconcile(now);

        assertThat(eventsFor(restriction)).containsExactly(NotificationEvent.ACTIVATED);
    }

    @Test
    void announcesACompletion() {
        Fixture fixture = givenOrganizationWithSlack();
        Instant now = Instant.now();
        ChangeRestriction restriction = given(fixture,
                now.minus(3, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), RestrictionStatus.ACTIVE);

        restrictionLifecycleService.reconcile(now);

        assertThat(eventsFor(restriction)).containsExactly(NotificationEvent.COMPLETED);
    }

    @Test
    void announcesNothingForARestrictionThatDidNotMove() {
        Fixture fixture = givenOrganizationWithSlack();
        Instant now = Instant.now();
        ChangeRestriction restriction = given(fixture,
                now.plus(5, ChronoUnit.HOURS), now.plus(6, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);

        restrictionLifecycleService.reconcile(now);

        assertThat(eventsFor(restriction)).isEmpty();
    }

    @Test
    void announcesNothingForACancelledRestrictionWhoseWindowPassed() {
        // Cancellation is terminal: it never activates, so there is nothing to announce.
        Fixture fixture = givenOrganizationWithSlack();
        Instant now = Instant.now();
        ChangeRestriction restriction = given(fixture,
                now.minus(3, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), RestrictionStatus.CANCELLED);

        restrictionLifecycleService.reconcile(now);

        assertThat(eventsFor(restriction)).isEmpty();
    }

    @Test
    void doesNotAnnounceTheSameTransitionOnEveryPass() {
        // Reconciliation is idempotent and runs on a timer; announcing on each pass would
        // notify an organization once a minute for the life of the restriction.
        Fixture fixture = givenOrganizationWithSlack();
        Instant now = Instant.now();
        ChangeRestriction restriction = given(fixture,
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);

        restrictionLifecycleService.reconcile(now);
        restrictionLifecycleService.reconcile(now);
        restrictionLifecycleService.reconcile(now);

        assertThat(eventsFor(restriction)).containsExactly(NotificationEvent.ACTIVATED);
    }

    @Test
    void announcesCompletionForAWindowMissedEntirelyWhileDown() {
        // Straight from SCHEDULED to COMPLETED — the restriction elapsed unannounced, and
        // the completion is still worth telling people about.
        Fixture fixture = givenOrganizationWithSlack();
        Instant now = Instant.now();
        ChangeRestriction restriction = given(fixture,
                now.minus(5, ChronoUnit.HOURS), now.minus(4, ChronoUnit.HOURS), RestrictionStatus.SCHEDULED);

        restrictionLifecycleService.reconcile(now);

        assertThat(eventsFor(restriction)).containsExactly(NotificationEvent.COMPLETED);
    }

}
