package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.freezhub.ContainersConfig;
import com.freezhub.audit.AuditActor;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
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
import com.freezhub.restriction.ChangeRestrictionService;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.restriction.RestrictionRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class NotificationOutboxTest {

    @Autowired
    private NotificationOutbox notificationOutbox;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private IntegrationRepository integrationRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private ChangeRestrictionService changeRestrictionService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private record Fixture(Long organizationId, Long userId) {
    }

    /** Stands in for the actor a controller would build; this test is about the outbox. */
    private AuditActor systemActorFor(Fixture fixture) {
        return new AuditActor(AuditActor.AuditActorType.USER, fixture.userId(), "admin@acme.test");
    }

    private Fixture given() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        User user = userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.MEMBER));
        return new Fixture(organization.getId(), user.getId());
    }

    private Integration givenIntegration(Long organizationId, IntegrationType type) {
        return integrationRepository.saveAndFlush(new Integration(organizationId, type, "{}"));
    }

    private ChangeRestriction givenRestriction(Fixture fixture) {
        Instant startsAt = Instant.now().plus(5, ChronoUnit.DAYS);
        return changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                fixture.organizationId(), "Freeze " + System.nanoTime(), null, "Reason",
                RestrictionLevel.HARD_FREEZE, startsAt, startsAt.plus(1, ChronoUnit.DAYS),
                fixture.userId(), Set.of(), Set.of(), Set.of()));
    }

    private List<Notification> notificationsFor(ChangeRestriction restriction) {
        return notificationRepository.findAllByRestrictionIdOrderByIdAsc(restriction.getId());
    }

    @Test
    void queuesOneRowPerEnabledDestination() {
        Fixture fixture = given();
        givenIntegration(fixture.organizationId(), IntegrationType.SLACK);
        givenIntegration(fixture.organizationId(), IntegrationType.EMAIL);
        ChangeRestriction restriction = givenRestriction(fixture);

        transactionTemplate.executeWithoutResult(status ->
                notificationOutbox.enqueue(
                        fixture.organizationId(), restriction.getId(), NotificationEvent.ACTIVATED));

        // Per-destination grain: Slack and email succeed or fail independently.
        assertThat(notificationsFor(restriction))
                .hasSize(2)
                .allSatisfy(notification -> {
                    assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
                    assertThat(notification.getAttempts()).isZero();
                    assertThat(notification.getSentAt()).isNull();
                });
    }

    @Test
    void skipsDisabledDestinations() {
        Fixture fixture = given();
        givenIntegration(fixture.organizationId(), IntegrationType.SLACK);
        Integration disabled = givenIntegration(fixture.organizationId(), IntegrationType.WEBHOOK);
        disabled.setEnabled(false);
        integrationRepository.saveAndFlush(disabled);
        ChangeRestriction restriction = givenRestriction(fixture);

        transactionTemplate.executeWithoutResult(status ->
                notificationOutbox.enqueue(
                        fixture.organizationId(), restriction.getId(), NotificationEvent.ACTIVATED));

        assertThat(notificationsFor(restriction)).hasSize(1);
    }

    @Test
    void queuesNothingWhenThereIsNowhereToSend() {
        // Correct, not a failure: an organization with no destinations has nobody to tell.
        Fixture fixture = given();
        ChangeRestriction restriction = givenRestriction(fixture);

        transactionTemplate.executeWithoutResult(status ->
                notificationOutbox.enqueue(
                        fixture.organizationId(), restriction.getId(), NotificationEvent.ACTIVATED));

        assertThat(notificationsFor(restriction)).isEmpty();
    }

    @Test
    void doesNotQueueTheSameEventTwice() {
        // The point of the outbox: a reconciliation that runs twice, or a restart mid
        // transition, must not notify anyone twice.
        Fixture fixture = given();
        givenIntegration(fixture.organizationId(), IntegrationType.SLACK);
        ChangeRestriction restriction = givenRestriction(fixture);

        transactionTemplate.executeWithoutResult(status ->
                notificationOutbox.enqueue(
                        fixture.organizationId(), restriction.getId(), NotificationEvent.ACTIVATED));
        transactionTemplate.executeWithoutResult(status ->
                notificationOutbox.enqueue(
                        fixture.organizationId(), restriction.getId(), NotificationEvent.ACTIVATED));

        assertThat(notificationsFor(restriction)).hasSize(1);
    }

    @Test
    void queuesDifferentEventsForTheSameRestriction() {
        Fixture fixture = given();
        givenIntegration(fixture.organizationId(), IntegrationType.SLACK);
        ChangeRestriction restriction = givenRestriction(fixture);

        transactionTemplate.executeWithoutResult(status -> {
            notificationOutbox.enqueue(
                    fixture.organizationId(), restriction.getId(), NotificationEvent.SCHEDULED);
            notificationOutbox.enqueue(
                    fixture.organizationId(), restriction.getId(), NotificationEvent.ACTIVATED);
        });

        assertThat(notificationsFor(restriction))
                .extracting(Notification::getEvent)
                .containsExactly(NotificationEvent.SCHEDULED, NotificationEvent.ACTIVATED);
    }

    @Test
    void refusesToQueueOutsideATransaction() {
        // Queuing outside the domain change's transaction would reintroduce exactly the
        // gap the outbox exists to close, so it fails loudly rather than silently.
        Fixture fixture = given();
        givenIntegration(fixture.organizationId(), IntegrationType.SLACK);
        ChangeRestriction restriction = givenRestriction(fixture);

        assertThatThrownBy(() -> notificationOutbox.enqueue(
                fixture.organizationId(), restriction.getId(), NotificationEvent.ACTIVATED))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void queuesOnlyForTheOwningOrganization() {
        Fixture owner = given();
        Fixture neighbour = given();
        givenIntegration(owner.organizationId(), IntegrationType.SLACK);
        givenIntegration(neighbour.organizationId(), IntegrationType.SLACK);
        ChangeRestriction restriction = givenRestriction(owner);

        transactionTemplate.executeWithoutResult(status ->
                notificationOutbox.enqueue(
                        owner.organizationId(), restriction.getId(), NotificationEvent.ACTIVATED));

        // A neighbouring tenant's Slack must never hear about this organization's freeze.
        assertThat(notificationsFor(restriction))
                .hasSize(1)
                .allSatisfy(notification ->
                        assertThat(notification.getOrganizationId()).isEqualTo(owner.organizationId()));
    }

    @Test
    void creatingARestrictionQueuesItsScheduledEvent() {
        // End to end through the real service: the outbox row is written in the same
        // transaction as the restriction itself.
        Fixture fixture = given();
        givenIntegration(fixture.organizationId(), IntegrationType.SLACK);
        Long environmentId = environmentRepository
                .saveAndFlush(new Environment(fixture.organizationId(), "prod-" + System.nanoTime())).getId();

        Instant startsAt = Instant.now().plus(3, ChronoUnit.DAYS);
        ChangeRestriction created = changeRestrictionService.create(
                fixture.organizationId(), systemActorFor(fixture),
                new RestrictionRequest("Announced freeze", null, "Reason", RestrictionLevel.HARD_FREEZE,
                        startsAt, startsAt.plus(1, ChronoUnit.DAYS),
                        new RestrictionRequest.ScopeRequest(null, null, Set.of(environmentId))));

        assertThat(notificationRepository.findAllByRestrictionIdOrderByIdAsc(created.getId()))
                .extracting(Notification::getEvent)
                .containsExactly(NotificationEvent.SCHEDULED);
    }

    @Test
    void cancellingARestrictionQueuesItsCancelledEvent() {
        Fixture fixture = given();
        givenIntegration(fixture.organizationId(), IntegrationType.SLACK);
        ChangeRestriction restriction = givenRestriction(fixture);

        changeRestrictionService.cancel(fixture.organizationId(), systemActorFor(fixture), restriction.getId());

        assertThat(notificationsFor(restriction))
                .extracting(Notification::getEvent)
                .contains(NotificationEvent.CANCELLED);
    }

}
