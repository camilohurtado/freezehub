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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dispatcher behaviour, independent of any channel (FZ-041).
 *
 * <p>Driven through a controllable EMAIL sender rather than Slack: this is about what the
 * dispatcher does with success, failure and unusual states, and the Slack HTTP call has
 * its own test. Using EMAIL also avoids competing with the real Slack sender for its slot
 * in the dispatcher's type map.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import({ContainersConfig.class, NotificationDispatchTest.ControllableSender.class})
class NotificationDispatchTest {

    @Autowired
    private NotificationDispatcher notificationDispatcher;

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
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RecordingEmailSender emailSender;

    @BeforeEach
    void resetSender() {
        emailSender.reset();
    }

    private record Fixture(Long restrictionId, Long integrationId) {
    }

    private Fixture given(IntegrationType type) {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        User user = userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.MEMBER));

        Integration destination = integrationRepository.saveAndFlush(new Integration(
                organization.getId(), type, "{\"recipients\":[\"a@acme.test\"]}"));

        Instant startsAt = Instant.now().plus(5, ChronoUnit.DAYS);
        ChangeRestriction restriction = changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                organization.getId(), "Freeze " + System.nanoTime(), null, "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE, startsAt, startsAt.plus(1, ChronoUnit.DAYS),
                user.getId(), Set.of(), Set.of(), Set.of()));

        transactionTemplate.executeWithoutResult(status -> notificationOutbox.enqueue(
                organization.getId(), restriction.getId(), NotificationEvent.ACTIVATED));

        return new Fixture(restriction.getId(), destination.getId());
    }

    private Notification notificationFor(Fixture fixture) {
        return notificationRepository.findAllByRestrictionIdOrderByIdAsc(fixture.restrictionId())
                .getFirst();
    }

    @Test
    void marksADeliveredNotificationSent() {
        Fixture fixture = given(IntegrationType.EMAIL);

        notificationDispatcher.dispatchPending();

        Notification delivered = notificationFor(fixture);
        assertThat(delivered.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(delivered.getSentAt()).isNotNull();
        assertThat(delivered.getAttempts()).isEqualTo(1);
        assertThat(delivered.getLastError()).isNull();
    }

    @Test
    void passesTheRestrictionToTheSender() {
        Fixture fixture = given(IntegrationType.EMAIL);

        notificationDispatcher.dispatchPending();

        assertThat(emailSender.deliveriesFor(fixture.restrictionId())).isEqualTo(1);
    }

    @Test
    void leavesAFailedDeliveryPendingWithTheReasonRecorded() {
        Fixture fixture = given(IntegrationType.EMAIL);
        emailSender.failFor(fixture.restrictionId(), "the mail server said no");

        notificationDispatcher.dispatchPending();

        Notification attempted = notificationFor(fixture);
        // Still PENDING so it remains eligible for retry; FZ-044 decides when to give up.
        assertThat(attempted.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(attempted.getAttempts()).isEqualTo(1);
        assertThat(attempted.getLastError()).contains("the mail server said no");
    }

    @Test
    void oneFailingDestinationDoesNotPreventOthersFromBeingDelivered() {
        // Each notification is delivered in its own transaction, so a batch is not
        // all-or-nothing — the reason the outbox is per-destination at all. Both are
        // dispatched in a *single* pass, with one destination failing.
        Fixture failing = given(IntegrationType.EMAIL);
        Fixture succeeding = given(IntegrationType.EMAIL);
        emailSender.failFor(failing.restrictionId(), "temporary outage");

        notificationDispatcher.dispatchPending();

        assertThat(notificationFor(succeeding).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notificationFor(failing).getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notificationFor(failing).getLastError()).contains("temporary outage");
    }

    @Test
    void doesNotSendToADestinationDisabledAfterQueueing() {
        // Queueing skips disabled destinations, so this is specifically the case where it
        // was switched off *after* the notification was queued. The organization's current
        // intent wins.
        Fixture fixture = given(IntegrationType.EMAIL);
        Integration destination = integrationRepository.findById(fixture.integrationId()).orElseThrow();
        destination.setEnabled(false);
        integrationRepository.saveAndFlush(destination);

        notificationDispatcher.dispatchPending();

        assertThat(emailSender.deliveriesFor(fixture.restrictionId())).isZero();
        Notification skipped = notificationFor(fixture);
        assertThat(skipped.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(skipped.getLastError()).contains("disabled");
        // Being switched off is not a delivery failure, so it costs no attempt: the
        // organization may re-enable the destination.
        assertThat(skipped.getAttempts()).isZero();
    }

    @Test
    void doesNotRetryAFailedDeliveryOnTheVeryNextPass() {
        // The defect this story fixes (OI-1): a failing destination used to be attempted
        // again on every single pass, for ever.
        Fixture fixture = given(IntegrationType.EMAIL);
        emailSender.failFor(fixture.restrictionId(), "temporary outage");
        Instant now = Instant.now();

        notificationDispatcher.dispatchPending(now);
        assertThat(notificationFor(fixture).getAttempts()).isEqualTo(1);

        // A pass moments later must leave it alone.
        notificationDispatcher.dispatchPending(now.plusSeconds(1));
        assertThat(notificationFor(fixture).getAttempts()).isEqualTo(1);

        // Once the backoff has elapsed it becomes eligible again.
        notificationDispatcher.dispatchPending(now.plus(Duration.ofMinutes(1)));
        assertThat(notificationFor(fixture).getAttempts()).isEqualTo(2);
    }

    @Test
    void givesUpAfterTheAttemptLimitAndSaysWhy() {
        // A permanently undeliverable announcement must become visibly FAILED rather than
        // being retried indefinitely — otherwise nobody learns it never arrived.
        Fixture fixture = given(IntegrationType.EMAIL);
        emailSender.failFor(fixture.restrictionId(), "destination is gone");
        Instant now = Instant.now();

        for (int pass = 0; pass < RetryPolicy.MAX_ATTEMPTS + 2; pass++) {
            // Each pass well past any backoff, so attempts are what limits it, not time.
            notificationDispatcher.dispatchPending(now.plus(Duration.ofHours(pass + 1)));
        }

        Notification abandoned = notificationFor(fixture);
        assertThat(abandoned.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(abandoned.getAttempts()).isEqualTo(RetryPolicy.MAX_ATTEMPTS);
        assertThat(abandoned.getLastError()).contains("destination is gone");
    }

    @Test
    void aTransientFailureFollowedBySuccessEndsSentWithNoStaleError() {
        // The earlier error must not linger as though it were the current state.
        Fixture fixture = given(IntegrationType.EMAIL);
        emailSender.failFor(fixture.restrictionId(), "blip");
        Instant now = Instant.now();

        notificationDispatcher.dispatchPending(now);
        assertThat(notificationFor(fixture).getLastError()).contains("blip");

        emailSender.succeedFor(fixture.restrictionId());
        notificationDispatcher.dispatchPending(now.plus(Duration.ofMinutes(1)));

        Notification delivered = notificationFor(fixture);
        assertThat(delivered.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(delivered.getLastError()).isNull();
    }

    @Test
    void leavesAChannelWithNoAdapterPendingRatherThanLosingIt() {
        // The webhook adapter is FZ-043; its notifications must wait for that story rather
        // than being marked delivered or discarded.
        Fixture fixture = given(IntegrationType.WEBHOOK);

        notificationDispatcher.dispatchPending();

        Notification waiting = notificationFor(fixture);
        assertThat(waiting.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(waiting.getLastError()).contains("No sender configured for channel WEBHOOK");
        // Deferred, not attempted: waiting for FZ-043 must not consume its retry budget
        // and abandon it before that adapter ever exists.
        assertThat(waiting.getAttempts()).isZero();
    }

    @Test
    void doesNotResendAnAlreadyDeliveredNotification() {
        Fixture fixture = given(IntegrationType.EMAIL);

        notificationDispatcher.dispatchPending();
        notificationDispatcher.dispatchPending();

        // A freeze announced twice is worse than late.
        assertThat(emailSender.deliveriesFor(fixture.restrictionId())).isEqualTo(1);
        assertThat(notificationFor(fixture).getAttempts()).isEqualTo(1);
    }

    /**
     * A sender whose outcome the test controls.
     *
     * <p>Records deliveries **per restriction**, not as a single counter: the dispatcher
     * drains the whole outbox, so a shared count would also include notifications other
     * tests left pending in the shared database.
     */
    static class RecordingEmailSender implements NotificationSender {

        private final Map<Long, Integer> deliveriesByRestriction = new ConcurrentHashMap<>();
        private final Set<Long> failing = ConcurrentHashMap.newKeySet();
        private volatile String failureMessage = "delivery failed";

        void reset() {
            deliveriesByRestriction.clear();
            failing.clear();
        }

        /** Makes delivery fail for one restriction only, leaving others to succeed. */
        void failFor(Long restrictionId, String message) {
            failing.add(restrictionId);
            failureMessage = message;
        }

        void succeedFor(Long restrictionId) {
            failing.remove(restrictionId);
        }

        int deliveriesFor(Long restrictionId) {
            return deliveriesByRestriction.getOrDefault(restrictionId, 0);
        }

        @Override
        public IntegrationType type() {
            return IntegrationType.EMAIL;
        }

        @Override
        public void send(Notification notification, ChangeRestriction restriction, Integration destination) {
            if (failing.contains(notification.getRestrictionId())) {
                throw new NotificationDeliveryException(failureMessage);
            }
            deliveriesByRestriction.merge(notification.getRestrictionId(), 1, Integer::sum);
        }
    }

    @TestConfiguration
    static class ControllableSender {

        @Bean
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

}
