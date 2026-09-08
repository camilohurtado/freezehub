package com.freezhub.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.apikey.ApiKeyService;
import com.freezhub.audit.AuditActor;
import com.freezhub.catalog.Application;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.shared.security.AuthenticatedUser;
import com.freezhub.shared.security.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Every check recorded, with what the caller told us about it (FZ-070). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class DeploymentCheckTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private DeploymentCheckRepository deploymentCheckRepository;

    @Autowired
    private DeploymentCheckRetention retention;

    private Long organizationId;
    private Long userId;
    private String apiKey;
    private String token;
    private Long environmentId;

    @BeforeEach
    void givenAnOrganizationWithACatalog() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        organizationId = organization.getId();
        String subject = "subject-" + System.nanoTime();
        User admin = userRepository.saveAndFlush(
                new User(organizationId, subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        userId = admin.getId();
        token = TestTokens.forSubject(jwtEncoder, subject);

        applicationRepository.saveAndFlush(new Application(organizationId, "payments-api"));
        environmentId = environmentRepository
                .saveAndFlush(new Environment(organizationId, "production")).getId();

        apiKey = apiKeyService.create(organizationId, AuditActor.of(
                new AuthenticatedUser(userId, organizationId, admin.getEmail(), admin.getRole())),
                "gitlab-ci").rawKey();
    }

    private void givenAFreezeInForce() {
        givenAFreezeInForceReturningId();
    }

    private Long givenAFreezeInForceReturningId() {
        Instant now = Instant.now();
        return changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                organizationId, "Black Friday Freeze", null, "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(1)), userId, Set.of(), Set.of(), Set.of(environmentId))).getId();
    }

    private void evaluate(String body) throws Exception {
        mockMvc.perform(post("/api/policy/evaluate")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void evaluate(String application, String environment) throws Exception {
        evaluate("{\"action\":\"DEPLOY\",\"application\":\"" + application
                + "\",\"environment\":\"" + environment + "\"}");
    }

    private List<DeploymentCheck> recorded() {
        return deploymentCheckRepository.findAll().stream()
                .filter(check -> check.getOrganizationId().equals(organizationId))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    @Test
    void recordsAnAllowedCheck() throws Exception {
        // Not only refusals, unlike the audit trail (FZ-060): a team's first question is
        // "did mine get through?", which a record of blocks alone cannot answer.
        evaluate("payments-api", "production");

        DeploymentCheck check = recorded().getLast();
        assertThat(check.getDecision().name()).isEqualTo("ALLOW");
        assertThat(check.getBlockedReason()).isNull();
        assertThat(check.getApplication()).isEqualTo("payments-api");
        assertThat(check.getApiKeyLabel()).isEqualTo("gitlab-ci");
    }

    @Test
    void recordsWhoAndWhatWhenTheCallerSaysSo() throws Exception {
        evaluate("{\"action\":\"DEPLOY\",\"application\":\"payments-api\",\"environment\":\"production\","
                + "\"actor\":\"engineer@acme.test\",\"reference\":\"a1b2c3d\","
                + "\"source\":\"https://gitlab.acme.test/pipelines/42\"}");

        DeploymentCheck check = recorded().getLast();
        assertThat(check.getActor()).isEqualTo("engineer@acme.test");
        assertThat(check.getReference()).isEqualTo("a1b2c3d");
        assertThat(check.getSource()).isEqualTo("https://gitlab.acme.test/pipelines/42");
    }

    @Test
    void acceptsACallerThatTellsUsNothing() throws Exception {
        // A pipeline written before this existed must keep working untouched.
        evaluate("payments-api", "production");

        DeploymentCheck check = recorded().getLast();
        assertThat(check.getActor()).isNull();
        assertThat(check.getReference()).isNull();
    }

    @Test
    void treatsAnUnsetCiVariableAsAbsent() throws Exception {
        // An unset variable arrives as an empty string, not as a missing field, and an
        // empty actor in the console is worse than none.
        evaluate("{\"action\":\"DEPLOY\",\"application\":\"payments-api\",\"environment\":\"production\","
                + "\"actor\":\"\",\"reference\":\"   \"}");

        DeploymentCheck check = recorded().getLast();
        assertThat(check.getActor()).isNull();
        assertThat(check.getReference()).isNull();
    }

    @Test
    void recordsWhichFreezeBlockedIt() throws Exception {
        // The view worth selling: who tried to deploy during the freeze, and what they
        // were told. The restriction is denormalised so a later rename cannot rewrite it.
        givenAFreezeInForce();

        evaluate("payments-api", "production");

        DeploymentCheck check = recorded().getLast();
        assertThat(check.getDecision().name()).isEqualTo("BLOCK");
        assertThat(check.getBlockedReason()).isEqualTo(BlockedReason.RESTRICTION);
        assertThat(check.getMatchedRestrictions()).contains("Black Friday Freeze").contains("HARD_FREEZE");
    }

    @Test
    void separatesAnUnregisteredNameFromARealFreeze() throws Exception {
        // They mean very different things to whoever reads the console.
        evaluate("payments-api", "prod");

        DeploymentCheck check = recorded().getLast();
        assertThat(check.getBlockedReason()).isEqualTo(BlockedReason.UNREGISTERED);
        // Stored as supplied, because the unrecognised name is the interesting part.
        assertThat(check.getEnvironment()).isEqualTo("prod");
    }

    @Test
    void showsChecksToAnyMemberNotOnlyAdministrators() throws Exception {
        // This is the screen a team reads to see whether their own deployment got
        // through; making them ask an administrator would defeat the point.
        evaluate("payments-api", "production");
        String memberSubject = "member-" + System.nanoTime();
        userRepository.saveAndFlush(
                new User(organizationId, memberSubject, memberSubject + "@acme.test", UserRole.MEMBER));

        mockMvc.perform(get("/api/deployment-checks")
                        .header("Authorization", "Bearer " + TestTokens.forSubject(jwtEncoder, memberSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].application", is("payments-api")))
                .andExpect(jsonPath("$[0].checkedBy", is("gitlab-ci")));
    }

    @Test
    void filtersToWhatWasRefused() throws Exception {
        givenAFreezeInForce();
        evaluate("payments-api", "production");
        evaluate("payments-api", "staging");

        mockMvc.perform(get("/api/deployment-checks")
                        .header("Authorization", "Bearer " + token)
                        .param("decision", "BLOCK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].decision", is("BLOCK")));
    }

    @Test
    void neverShowsAnotherOrganizationsChecks() throws Exception {
        evaluate("payments-api", "production");

        Organization other =
                organizationRepository.saveAndFlush(new Organization("Other " + System.nanoTime()));
        String otherSubject = "other-" + System.nanoTime();
        userRepository.saveAndFlush(
                new User(other.getId(), otherSubject, otherSubject + "@other.test", UserRole.ADMINISTRATOR));

        mockMvc.perform(get("/api/deployment-checks")
                        .header("Authorization", "Bearer " + TestTokens.forSubject(jwtEncoder, otherSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/deployment-checks")).andExpect(status().isUnauthorized());
    }

    @Test
    void keepsChecksInsideTheOrganizationsRetentionAndPurgesTheRest() throws Exception {
        // The only thing bounding a table written once per deployment — and the only thing
        // bounding how long the deploying engineer's identity is held.
        evaluate("payments-api", "production");
        Long id = recorded().getLast().getId();

        // A year and a day later, with the default 365-day retention.
        int removed = retention.purge(Instant.now().plus(Duration.ofDays(366)));

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(deploymentCheckRepository.findById(id)).isEmpty();
    }

    @Test
    void honoursEachOrganizationsOwnRetention() throws Exception {
        // A customer keeping five years must not be truncated to another's ninety days.
        evaluate("payments-api", "production");
        Long id = recorded().getLast().getId();
        Organization organization = organizationRepository.findById(organizationId).orElseThrow();
        organization.setDeploymentCheckRetentionDays(3650);
        organizationRepository.saveAndFlush(organization);

        retention.purge(Instant.now().plus(Duration.ofDays(366)));

        assertThat(deploymentCheckRepository.findById(id)).isPresent();
    }

    @Test
    void doesNotRecordAFailedRequestAsACheck() throws Exception {
        // A malformed body never reached a decision, so there is nothing to record — a
        // console entry with no answer would be worse than no entry.
        int before = recorded().size();

        mockMvc.perform(post("/api/policy/evaluate")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DESTROY\",\"application\":\"payments-api\",\"environment\":\"production\"}"))
                .andExpect(status().isBadRequest());

        assertThat(recorded()).hasSize(before);
    }

    @Test
    void doesNotShowTheApiKeyItself() throws Exception {
        evaluate("payments-api", "production");

        mockMvc.perform(get("/api/deployment-checks").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$[0].matchedRestrictions", is(nullValue())));
    }


    @Test
    void theSummaryNeedsAuthenticationLikeEverythingElse() throws Exception {
        mockMvc.perform(get("/api/deployment-checks/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSummaryCountsChecksTheGateActuallyAnswered() throws Exception {
        // End to end on purpose (FZ-105). The refusals-per-restriction figure is read out
        // of the denormalised JSON with a native query, so the only verification worth
        // having is one where PolicyService wrote that JSON — a fixture hand-rolled in a
        // test would prove the query parses a shape nothing produces.
        // Allowed while nothing is in force, refused once the freeze exists. An
        // unrecognised environment is itself a refusal, so both checks name a real one.
        evaluate("payments-api", "production");
        Long restrictionId = givenAFreezeInForceReturningId();
        evaluate("payments-api", "production");

        mockMvc.perform(get("/api/deployment-checks/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.allowed", is(1)))
                .andExpect(jsonPath("$.today.refused", is(1)))
                .andExpect(jsonPath("$.today.total", is(2)))
                // One name has asked, of one in the catalog.
                .andExpect(jsonPath("$.applications.seen", is(1)))
                .andExpect(jsonPath("$.applications.total", is(1)))
                .andExpect(jsonPath("$.daily", hasSize(14)))
                .andExpect(jsonPath("$.refusalsByRestriction", hasSize(1)))
                .andExpect(jsonPath("$.refusalsByRestriction[0].restrictionId",
                        is(restrictionId.intValue())))
                .andExpect(jsonPath("$.refusalsByRestriction[0].refused", is(1)));
    }

    @Test
    void theSummaryNeverCountsAnotherOrganizationsChecks() throws Exception {
        givenAFreezeInForce();
        evaluate("payments-api", "production");

        // A second organization, with its own administrator and nothing else.
        Organization other = organizationRepository
                .saveAndFlush(new Organization("Globex " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        userRepository.saveAndFlush(new User(other.getId(), subject,
                subject + "@globex.test", UserRole.ADMINISTRATOR));

        mockMvc.perform(get("/api/deployment-checks/summary")
                        .header("Authorization", "Bearer " + TestTokens.forSubject(jwtEncoder, subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.total", is(0)))
                .andExpect(jsonPath("$.applications.seen", is(0)))
                .andExpect(jsonPath("$.refusalsByRestriction", hasSize(0)));
    }
}
