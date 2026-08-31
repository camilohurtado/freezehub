package com.freezhub.restriction;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationRepository;
import com.freezhub.integration.IntegrationType;
import com.freezhub.notification.Notification;
import com.freezhub.notification.NotificationEvent;
import com.freezhub.notification.NotificationRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The "starting soon" announcement (FZ-047, fixes {@code OI-3}).
 *
 * <p>Driven against an explicit instant rather than by waiting, like the lifecycle
 * reconciler it runs beside.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class StartingSoonNotificationTest {

    @Autowired
    private StartingSoonNotifier startingSoonNotifier;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IntegrationRepository integrationRepository;

    @Autowired
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private record Tenant(Long organizationId, Long userId) {
    }

    private Tenant givenOrganization(Integer leadTimeMinutes) {
        Organization organization = new Organization("Acme " + System.nanoTime());
        if (leadTimeMinutes != null) {
            organization.setStartingSoonLeadTimeMinutes(leadTimeMinutes);
        }
        organization = organizationRepository.saveAndFlush(organization);

        String subject = "subject-" + System.nanoTime();
        User user = userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        integrationRepository.saveAndFlush(new Integration(
                organization.getId(), IntegrationType.EMAIL, "{\"recipients\":[\"a@acme.test\"]}"));

        return new Tenant(organization.getId(), user.getId());
    }

    private ChangeRestriction givenRestrictionStartingAt(Tenant tenant, Instant startsAt) {
        return changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                tenant.organizationId(), "Freeze " + System.nanoTime(), null, "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE, startsAt, startsAt.plus(Duration.ofDays(1)),
                tenant.userId(), Set.of(), Set.of(), Set.of()));
    }

    private List<Notification> startingSoonFor(ChangeRestriction restriction) {
        return notificationRepository.findAllByRestrictionIdOrderByIdAsc(restriction.getId()).stream()
                .filter(notification -> notification.getEvent() == NotificationEvent.STARTING_SOON)
                .toList();
    }

    @Test
    void announcesARestrictionInsideTheWarningWindow() {
        Instant now = Instant.now();
        Tenant tenant = givenOrganization(null);
        ChangeRestriction soon = givenRestrictionStartingAt(tenant, now.plus(Duration.ofHours(6)));

        startingSoonNotifier.announceApproaching(now);

        assertThat(startingSoonFor(soon)).hasSize(1);
    }

    @Test
    void defaultsToADaysNotice() {
        Instant now = Instant.now();
        Tenant tenant = givenOrganization(null);
        ChangeRestriction justInside = givenRestrictionStartingAt(tenant, now.plus(Duration.ofHours(23)));
        ChangeRestriction justOutside = givenRestrictionStartingAt(tenant, now.plus(Duration.ofHours(25)));

        startingSoonNotifier.announceApproaching(now);

        assertThat(startingSoonFor(justInside)).hasSize(1);
        assertThat(startingSoonFor(justOutside)).isEmpty();
    }

    @Test
    void honoursEachOrganizationsOwnLeadTime() {
        // The reason this setting is per-organization: the same restriction timing is
        // "soon" for one tenant and not yet for another.
        Instant now = Instant.now();
        Tenant patient = givenOrganization(60);
        Tenant eager = givenOrganization(10_080);
        ChangeRestriction theirs = givenRestrictionStartingAt(patient, now.plus(Duration.ofDays(2)));
        ChangeRestriction ours = givenRestrictionStartingAt(eager, now.plus(Duration.ofDays(2)));

        startingSoonNotifier.announceApproaching(now);

        assertThat(startingSoonFor(theirs)).isEmpty();
        assertThat(startingSoonFor(ours)).hasSize(1);
    }

    @Test
    void announcesOnlyOnceHoweverOftenTheSweepRuns() {
        // The whole difficulty of a time-triggered event: a restriction stays inside its
        // window for the entire lead time, so it is seen again on every single pass.
        // Announcing each time would be worse than not announcing at all.
        Instant now = Instant.now();
        Tenant tenant = givenOrganization(null);
        ChangeRestriction soon = givenRestrictionStartingAt(tenant, now.plus(Duration.ofHours(6)));

        startingSoonNotifier.announceApproaching(now);
        startingSoonNotifier.announceApproaching(now.plus(Duration.ofMinutes(1)));
        startingSoonNotifier.announceApproaching(now.plus(Duration.ofHours(1)));

        assertThat(startingSoonFor(soon)).hasSize(1);
    }

    @Test
    void doesNotAnnounceARestrictionThatHasAlreadyStarted() {
        // ACTIVATED covers that, and warning about something already in force is noise.
        Instant now = Instant.now();
        Tenant tenant = givenOrganization(null);
        ChangeRestriction alreadyRunning = givenRestrictionStartingAt(tenant, now.minus(Duration.ofHours(1)));

        startingSoonNotifier.announceApproaching(now);

        assertThat(startingSoonFor(alreadyRunning)).isEmpty();
    }

    @Test
    void doesNotAnnounceACancelledRestriction() {
        Instant now = Instant.now();
        Tenant tenant = givenOrganization(null);
        ChangeRestriction cancelled = givenRestrictionStartingAt(tenant, now.plus(Duration.ofHours(6)));
        cancelled.cancel();
        changeRestrictionRepository.saveAndFlush(cancelled);

        startingSoonNotifier.announceApproaching(now);

        assertThat(startingSoonFor(cancelled)).isEmpty();
    }

    @Test
    void announcesToEachOrganizationsOwnDestinationsOnly() {
        Instant now = Instant.now();
        Tenant ours = givenOrganization(null);
        Tenant theirs = givenOrganization(null);
        ChangeRestriction soon = givenRestrictionStartingAt(ours, now.plus(Duration.ofHours(6)));

        startingSoonNotifier.announceApproaching(now);

        List<Notification> queued = startingSoonFor(soon);
        assertThat(queued).hasSize(1);
        assertThat(queued.getFirst().getOrganizationId()).isEqualTo(ours.organizationId());
        assertThat(queued.getFirst().getOrganizationId()).isNotEqualTo(theirs.organizationId());
    }

}
