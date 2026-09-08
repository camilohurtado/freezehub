package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
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
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.shared.security.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** What was announced, and whether each channel accepted it (FZ-115). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class NotificationHistoryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private NotificationHistoryService history;

    @Autowired
    private NotificationRetryService retries;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private EnvironmentRepository environments;

    @Autowired
    private ChangeRestrictionRepository restrictions;

    @Autowired
    private IntegrationRepository integrations;

    @Autowired
    private JdbcTemplate jdbc;

    private Long organizationId;
    private Long userId;
    private Long restrictionId;
    private String adminToken;

    @BeforeEach
    void setUp() {
        organizationId = organizations
                .saveAndFlush(new Organization("Acme " + System.nanoTime())).getId();
        String subject = "subject-" + System.nanoTime();
        User admin = users.saveAndFlush(
                new User(organizationId, subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        userId = admin.getId();
        adminToken = TestTokens.forSubject(jwtEncoder, subject);

        Long environmentId = environments
                .saveAndFlush(new Environment(organizationId, "production")).getId();
        Instant now = Instant.now();
        restrictionId = restrictions.saveAndFlush(new ChangeRestriction(
                organizationId, "Black Friday Freeze", null, "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(1)), userId, Set.of(), Set.of(),
                Set.of(environmentId))).getId();
    }

    private Long channel(IntegrationType type) {
        return integrations
                .saveAndFlush(new Integration(organizationId, type, "{\"webhookUrl\":\"x\"}")).getId();
    }

    /** Written straight to the outbox: the dispatcher's own path is FZ-041's to test. */
    private void delivery(Long integrationId, NotificationEvent event, NotificationStatus status,
                          int attempts, String lastError, Instant createdAt) {
        jdbc.update("""
                insert into notification
                  (organization_id, restriction_id, integration_id, event, status, attempts,
                   last_error, created_at, updated_at, next_attempt_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                organizationId, restrictionId, integrationId, event.name(), status.name(),
                attempts, lastError,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt));
    }

    @Test
    void groupsTheChannelsOfOneEventIntoOneRow() {
        // Three rows saying the same thing happened, one of which quietly says it did not
        // arrive. Grouped, the gap is visible rather than derivable.
        Instant at = Instant.now().minus(Duration.ofMinutes(5));
        delivery(channel(IntegrationType.SLACK), NotificationEvent.ACTIVATED,
                NotificationStatus.SENT, 1, null, at);
        delivery(channel(IntegrationType.EMAIL), NotificationEvent.ACTIVATED,
                NotificationStatus.SENT, 1, null, at);
        delivery(channel(IntegrationType.WEBHOOK), NotificationEvent.ACTIVATED,
                NotificationStatus.FAILED, 5, "502 Bad Gateway", at);

        var events = history.history(organizationId, 50);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.event()).isEqualTo(NotificationEvent.ACTIVATED);
            assertThat(event.restrictionName()).isEqualTo("Black Friday Freeze");
            assertThat(event.deliveries()).hasSize(3);
            assertThat(event.hasFailure()).isTrue();
        });
    }

    @Test
    void keepsTheFailuresReasonAndAttemptCount() {
        // "Was Slack told?" is answered by delivered/failed. "Why not?" needs the rest.
        delivery(channel(IntegrationType.WEBHOOK), NotificationEvent.ACTIVATED,
                NotificationStatus.FAILED, 5, "502 Bad Gateway", Instant.now());

        var delivery = history.history(organizationId, 50).getFirst().deliveries().getFirst();

        assertThat(delivery.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(delivery.lastError()).isEqualTo("502 Bad Gateway");
        assertThat(delivery.attempts()).isEqualTo(5);
        assertThat(delivery.channel()).isEqualTo(IntegrationType.WEBHOOK);
    }

    @Test
    void separatesDifferentEventsAboutTheSameRestriction() {
        Long slack = channel(IntegrationType.SLACK);
        delivery(slack, NotificationEvent.SCHEDULED, NotificationStatus.SENT, 1, null,
                Instant.now().minus(Duration.ofHours(2)));
        delivery(slack, NotificationEvent.ACTIVATED, NotificationStatus.SENT, 1, null,
                Instant.now().minus(Duration.ofMinutes(1)));

        var events = history.history(organizationId, 50);

        assertThat(events).hasSize(2);
        // Newest first: the most recent thing that happened is the thing being asked about.
        assertThat(events.getFirst().event()).isEqualTo(NotificationEvent.ACTIVATED);
    }

    @Test
    void aCancelledRestrictionKeepsItsHistory() {
        // Cancelling is a status change, not a delete (FZ-024) — which is what keeps the
        // announcement readable afterwards. A real delete would cascade the history away,
        // and the product has no path that does one.
        delivery(channel(IntegrationType.SLACK), NotificationEvent.CANCELLED,
                NotificationStatus.SENT, 1, null, Instant.now());

        var event = history.history(organizationId, 50).getFirst();

        assertThat(event.event()).isEqualTo(NotificationEvent.CANCELLED);
        assertThat(event.restrictionName()).isEqualTo("Black Friday Freeze");
    }

    @Test
    void anotherOrganizationsAnnouncementsAreNeverListed() {
        delivery(channel(IntegrationType.SLACK), NotificationEvent.ACTIVATED,
                NotificationStatus.SENT, 1, null, Instant.now());
        Long other = organizations
                .saveAndFlush(new Organization("Globex " + System.nanoTime())).getId();

        assertThat(history.history(other, 50)).isEmpty();
    }

    @Test
    void theEndpointNeedsAnAdministrator() throws Exception {
        String subject = "member-" + System.nanoTime();
        users.saveAndFlush(
                new User(organizationId, subject, subject + "@acme.test", UserRole.MEMBER));

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + TestTokens.forSubject(jwtEncoder, subject)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void theEndpointNeedsAuthentication() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void retryingRequeuesOnlyTheDeliveriesThatFailed() {
        // Re-sending the whole event would announce a freeze a second time to every
        // channel that already accepted it — a fix for one person becoming a duplicate
        // for everyone else (FZ-119).
        Long slack = channel(IntegrationType.SLACK);
        Long webhook = channel(IntegrationType.WEBHOOK);
        Instant at = Instant.now().minus(Duration.ofHours(1));
        delivery(slack, NotificationEvent.ACTIVATED, NotificationStatus.SENT, 1, null, at);
        delivery(webhook, NotificationEvent.ACTIVATED, NotificationStatus.FAILED, 6, "502", at);

        int requeued = retries.retry(
                organizationId, restrictionId, NotificationEvent.ACTIVATED, Instant.now());

        assertThat(requeued).isEqualTo(1);
        var deliveries = history.history(organizationId, 50).getFirst().deliveries();
        assertThat(deliveries)
                .filteredOn(d -> d.integrationId().equals(webhook))
                .singleElement()
                .satisfies(d -> assertThat(d.status()).isEqualTo(NotificationStatus.PENDING));
        assertThat(deliveries)
                .filteredOn(d -> d.integrationId().equals(slack))
                .singleElement()
                .satisfies(d -> assertThat(d.status()).isEqualTo(NotificationStatus.SENT));
    }

    @Test
    void retryingResetsTheAttemptCountSoTheDispatcherWillTryAgain() {
        // RetryPolicy calls a row exhausted at six attempts. Requeuing without resetting
        // would abandon it again without a single new attempt — the retry would appear to
        // work and change nothing.
        Long webhook = channel(IntegrationType.WEBHOOK);
        delivery(webhook, NotificationEvent.ACTIVATED, NotificationStatus.FAILED, 6, "502",
                Instant.now().minus(Duration.ofHours(1)));

        retries.retry(organizationId, restrictionId, NotificationEvent.ACTIVATED, Instant.now());

        var delivery = history.history(organizationId, 50).getFirst().deliveries().getFirst();
        assertThat(delivery.attempts()).isZero();
        assertThat(RetryPolicy.isExhausted(delivery.attempts())).isFalse();
    }

    @Test
    void retryingKeepsWhyItFailedUntilSomethingReplacesIt() {
        // While it sits queued, the previous error is still the only account of what went
        // wrong. Clearing it would leave a pending row with no history.
        delivery(channel(IntegrationType.WEBHOOK), NotificationEvent.ACTIVATED,
                NotificationStatus.FAILED, 6, "502 Bad Gateway", Instant.now());

        retries.retry(organizationId, restrictionId, NotificationEvent.ACTIVATED, Instant.now());

        assertThat(history.history(organizationId, 50).getFirst().deliveries().getFirst().lastError())
                .isEqualTo("502 Bad Gateway");
    }

    @Test
    void retryingAnEventWithNothingFailedChangesNothing() {
        delivery(channel(IntegrationType.SLACK), NotificationEvent.ACTIVATED,
                NotificationStatus.SENT, 1, null, Instant.now());

        assertThat(retries.retry(organizationId, restrictionId, NotificationEvent.ACTIVATED,
                Instant.now())).isZero();
        assertThat(history.history(organizationId, 50).getFirst().deliveries().getFirst().status())
                .isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void retryingNeverReachesAnotherOrganizationsDeliveries() {
        delivery(channel(IntegrationType.WEBHOOK), NotificationEvent.ACTIVATED,
                NotificationStatus.FAILED, 6, "502", Instant.now());
        Long other = organizations
                .saveAndFlush(new Organization("Globex " + System.nanoTime())).getId();

        assertThat(retries.retry(other, restrictionId, NotificationEvent.ACTIVATED, Instant.now()))
                .isZero();
        assertThat(history.history(organizationId, 50).getFirst().deliveries().getFirst().status())
                .isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void onlyAnAdministratorCanRetry() throws Exception {
        String subject = "member-" + System.nanoTime();
        users.saveAndFlush(
                new User(organizationId, subject, subject + "@acme.test", UserRole.MEMBER));

        mockMvc.perform(post("/api/notifications/retry")
                        .header("Authorization", "Bearer " + TestTokens.forSubject(jwtEncoder, subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restrictionId\":" + restrictionId + ",\"event\":\"ACTIVATED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRetryEndpointReportsHowManyItRequeued() throws Exception {
        delivery(channel(IntegrationType.WEBHOOK), NotificationEvent.ACTIVATED,
                NotificationStatus.FAILED, 6, "502", Instant.now());

        mockMvc.perform(post("/api/notifications/retry")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restrictionId\":" + restrictionId + ",\"event\":\"ACTIVATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued", is(1)));
    }
}
