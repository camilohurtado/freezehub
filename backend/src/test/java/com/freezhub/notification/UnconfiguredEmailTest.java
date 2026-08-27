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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What happens to a notification for a channel that has no usable sender.
 *
 * <p>No {@code freezehub.notifications.email.from} is configured here — which is exactly
 * the production situation before someone sets up email — so `EmailNotificationSender` is
 * not registered. This deliberately does **not** import the recording sender that
 * {@link NotificationDispatchTest} uses, or there would be one after all.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class UnconfiguredEmailTest {

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

    @Test
    void defersWithoutConsumingAnAttemptWhenTheChannelHasNoSender() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        User user = userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.MEMBER));
        integrationRepository.saveAndFlush(new Integration(
                organization.getId(), IntegrationType.EMAIL, "{\"recipients\":[\"a@acme.test\"]}"));

        Instant startsAt = Instant.now().plus(5, ChronoUnit.DAYS);
        ChangeRestriction restriction = changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                organization.getId(), "Freeze " + System.nanoTime(), null, "Reason",
                RestrictionLevel.HARD_FREEZE, startsAt, startsAt.plus(1, ChronoUnit.DAYS),
                user.getId(), Set.of(), Set.of(), Set.of()));

        transactionTemplate.executeWithoutResult(status -> notificationOutbox.enqueue(
                organization.getId(), restriction.getId(), NotificationEvent.ACTIVATED));

        // Run it more times than the retry budget allows.
        Instant now = Instant.now();
        for (int pass = 0; pass < RetryPolicy.MAX_ATTEMPTS + 3; pass++) {
            notificationDispatcher.dispatchPending(now.plus(pass + 1, ChronoUnit.HOURS));
        }

        Notification waiting =
                notificationRepository.findAllByRestrictionIdOrderByIdAsc(restriction.getId()).getFirst();

        // The point: an unconfigured channel must not burn the retry budget and abandon
        // the notification before anyone gets around to configuring it.
        assertThat(waiting.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(waiting.getAttempts()).isZero();
        assertThat(waiting.getLastError()).contains("No sender configured for channel EMAIL");
    }

}
