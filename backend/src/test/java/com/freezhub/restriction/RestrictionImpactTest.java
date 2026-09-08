package com.freezhub.restriction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** What one restriction actually did (FZ-112). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class RestrictionImpactTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private RestrictionImpactService impacts;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private EnvironmentRepository environments;

    @Autowired
    private ChangeRestrictionRepository restrictions;

    @Autowired
    private JdbcTemplate jdbc;

    private Long organizationId;
    private Long restrictionId;
    private String token;

    @BeforeEach
    void setUp() {
        organizationId = organizations
                .saveAndFlush(new Organization("Acme " + System.nanoTime())).getId();
        String subject = "subject-" + System.nanoTime();
        User admin = users.saveAndFlush(
                new User(organizationId, subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        token = TestTokens.forSubject(jwtEncoder, subject);

        Long environmentId = environments
                .saveAndFlush(new Environment(organizationId, "production")).getId();
        Instant now = Instant.now();
        restrictionId = restrictions.saveAndFlush(new ChangeRestriction(
                organizationId, "Black Friday Freeze", null, "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE, now.minus(Duration.ofHours(2)),
                now.plus(Duration.ofHours(2)), admin.getId(), Set.of(), Set.of(),
                Set.of(environmentId))).getId();
    }

    private String matched(Object... idThenLevel) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < idThenLevel.length; i += 2) {
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(idThenLevel[i])
                .append(",\"name\":\"r\",\"level\":\"").append(idThenLevel[i + 1]).append("\"}");
        }
        return json.append(']').toString();
    }

    private void refusal(String application, String matched) {
        jdbc.update("""
                insert into deployment_check
                  (organization_id, api_key_label, application, environment, decision,
                   matched_restrictions, checked_at)
                values (?, 'ci', ?, 'production', 'BLOCK', ?, ?)
                """, organizationId, application, matched,
                OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    }

    @Test
    void countsWhatItRefusedAndHowFarItReached() {
        refusal("payments-api", matched(restrictionId, "HARD_FREEZE"));
        refusal("payments-api", matched(restrictionId, "HARD_FREEZE"));
        refusal("checkout-web", matched(restrictionId, "HARD_FREEZE"));

        var impact = impacts.of(organizationId, restrictionId);

        assertThat(impact.checksRefused()).isEqualTo(3);
        // Three refusals, two pipelines — the second figure is not the first.
        assertThat(impact.pipelinesAffected()).isEqualTo(2);
    }

    @Test
    void doesNotCreditAnAdvisoryThatMerelyMatched() {
        // The rule 1a's table already validates, applied to one restriction: a blocked
        // check names every restriction that matched, and an advisory rode along.
        refusal("payments-api", matched(restrictionId, "ADVISORY"));

        var impact = impacts.of(organizationId, restrictionId);

        assertThat(impact.checksRefused()).isZero();
        assertThat(impact.pipelinesAffected()).isZero();
    }

    @Test
    void doesNotCountAnotherRestrictionsRefusals() {
        refusal("payments-api", matched(9999, "HARD_FREEZE"));

        assertThat(impacts.of(organizationId, restrictionId).checksRefused()).isZero();
    }

    @Test
    void countsTheRefusalWhenSeveralRestrictionsMatchedTogether() {
        // Overlaps are allowed (FZ-020), so a check can be refused by two freezes at once
        // and both of them stopped that deployment.
        refusal("payments-api", matched(restrictionId, "HARD_FREEZE", 9999, "HARD_FREEZE"));

        assertThat(impacts.of(organizationId, restrictionId).checksRefused()).isEqualTo(1);
    }

    @Test
    void countsTheAnnouncementsAndTheOnesThatFailed() {
        Long integrationId = jdbc.queryForObject("""
                insert into integration (organization_id, type, enabled, config)
                values (?, 'SLACK', true, '{}') returning id
                """, Long.class, organizationId);
        jdbc.update("""
                insert into notification
                  (organization_id, restriction_id, integration_id, event, status, attempts,
                   created_at, updated_at, next_attempt_at)
                values (?, ?, ?, 'ACTIVATED', 'SENT', 1, now(), now(), now()),
                       (?, ?, ?, 'COMPLETED', 'FAILED', 6, now(), now(), now())
                """, organizationId, restrictionId, integrationId,
                organizationId, restrictionId, integrationId);

        var impact = impacts.of(organizationId, restrictionId);

        assertThat(impact.notificationsSent()).isEqualTo(2);
        assertThat(impact.notificationsFailed()).isEqualTo(1);
    }

    @Test
    void aRestrictionThatDidNothingReportsZeroesRatherThanNulls() {
        // The screen renders these unconditionally; a null would be a blank where a
        // nought belongs.
        var impact = impacts.of(organizationId, restrictionId);

        assertThat(impact.checksRefused()).isZero();
        assertThat(impact.pipelinesAffected()).isZero();
        assertThat(impact.notificationsSent()).isZero();
        assertThat(impact.notificationsFailed()).isZero();
    }

    @Test
    void anotherOrganizationsRestrictionIsNotFoundRatherThanForbidden() throws Exception {
        // 404 not 403, so cross-tenant existence is never revealed.
        Long other = organizations
                .saveAndFlush(new Organization("Globex " + System.nanoTime())).getId();
        String subject = "globex-" + System.nanoTime();
        users.saveAndFlush(new User(other, subject, subject + "@globex.test", UserRole.ADMINISTRATOR));

        mockMvc.perform(get("/api/restrictions/" + restrictionId + "/impact")
                        .header("Authorization", "Bearer " + TestTokens.forSubject(jwtEncoder, subject)))
                .andExpect(status().isNotFound());
    }

    @Test
    void servesTheFiguresOverHttp() throws Exception {
        refusal("payments-api", matched(restrictionId, "HARD_FREEZE"));

        mockMvc.perform(get("/api/restrictions/" + restrictionId + "/impact")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
