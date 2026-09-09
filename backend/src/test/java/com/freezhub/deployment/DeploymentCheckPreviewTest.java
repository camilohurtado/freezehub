package com.freezhub.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * "Can I deploy?", asked by a person (FZ-120).
 *
 * <p>Two properties are the story: the answer is the machine endpoint's answer, and asking
 * it writes nothing down.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class DeploymentCheckPreviewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private ApplicationRepository applications;

    @Autowired
    private EnvironmentRepository environments;

    @Autowired
    private ChangeRestrictionRepository restrictions;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private JdbcTemplate jdbc;

    private Long organizationId;
    private Long userId;
    private String token;
    private String apiKey;

    @BeforeEach
    void givenASignedInPersonAndACatalog() {
        organizationId = organizations
                .saveAndFlush(new Organization("Acme " + System.nanoTime())).getId();

        String subject = "subject-" + System.nanoTime();
        User admin = users.saveAndFlush(
                new User(organizationId, subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        userId = admin.getId();
        token = TestTokens.forSubject(jwtEncoder, subject);

        applications.saveAndFlush(new Application(organizationId, "payments-api"));
        environments.saveAndFlush(new Environment(organizationId, "production"));

        apiKey = apiKeys.create(organizationId, AuditActor.of(new AuthenticatedUser(
                userId, organizationId, admin.getEmail(), admin.getRole())), "gitlab-ci").rawKey();
    }

    private ResultActions preview(String application, String environment) throws Exception {
        return mockMvc.perform(get("/api/deployment-checks/preview")
                .param("application", application)
                .param("environment", environment)
                .header("Authorization", "Bearer " + token));
    }

    private ChangeRestriction givenFreeze(RestrictionLevel level, String name) {
        Instant now = Instant.now();
        Long production = environments
                .findByOrganizationIdAndName(organizationId, "production").orElseThrow().getId();
        return restrictions.saveAndFlush(new ChangeRestriction(
                organizationId, name, null, "Revenue-critical period", level,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), userId,
                Set.of(), Set.of(), Set.of(production)));
    }

    private int checksRecorded() {
        return jdbc.queryForObject(
                "select count(*) from deployment_check where organization_id = ?",
                Integer.class, organizationId);
    }

    private int auditEntries() {
        return jdbc.queryForObject(
                "select count(*) from audit_event where organization_id = ?",
                Integer.class, organizationId);
    }

    @Test
    void requiresASignedInPerson() throws Exception {
        mockMvc.perform(get("/api/deployment-checks/preview")
                        .param("application", "payments-api")
                        .param("environment", "production"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void answersAllowWhenNothingIsInForce() throws Exception {
        preview("payments-api", "production")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("ALLOW")))
                .andExpect(jsonPath("$.application", is("payments-api")))
                .andExpect(jsonPath("$.environment", is("production")))
                .andExpect(jsonPath("$.restrictions", is(empty())))
                .andExpect(jsonPath("$.unregistered", is(empty())))
                .andExpect(jsonPath("$.evaluatedAt").exists());
    }

    @Test
    void blocksOnAHardFreezeAndSaysWhichAndWhy() throws Exception {
        // The reason is carried for the same purpose as on the machine answer: somebody
        // told only "BLOCK" cannot act on it.
        ChangeRestriction freeze = givenFreeze(RestrictionLevel.HARD_FREEZE, "Black Friday Freeze");

        preview("payments-api", "production")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.restrictions", hasSize(1)))
                .andExpect(jsonPath("$.restrictions[0].id", is(freeze.getId().intValue())))
                .andExpect(jsonPath("$.restrictions[0].reason", is("Revenue-critical period")))
                .andExpect(jsonPath("$.message", containsString("Black Friday Freeze")));
    }

    @Test
    void reportsAnAdvisoryWithoutRefusing() throws Exception {
        givenFreeze(RestrictionLevel.ADVISORY, "Year-end change window");

        preview("payments-api", "production")
                .andExpect(jsonPath("$.decision", is("ALLOW")))
                .andExpect(jsonPath("$.restrictions", hasSize(1)))
                .andExpect(jsonPath("$.restrictions[0].level", is("ADVISORY")));
    }

    @Test
    void anUnrecognisedNameIsReportedRatherThanQuietlyAllowed() throws Exception {
        // The same rule as the gate: a name nobody registered cannot be evaluated, so it
        // cannot be told it may deploy.
        preview("checkout-web", "production")
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.unregistered", contains("APPLICATION")))
                .andExpect(jsonPath("$.message", containsString("checkout-web")));
    }

    @Test
    void cannotAskAboutAnotherOrganizationsApplication() throws Exception {
        Long other = organizations
                .saveAndFlush(new Organization("Contoso " + System.nanoTime())).getId();
        applications.saveAndFlush(new Application(other, "billing-api"));

        // Unregistered, not found: the organization comes from the credential, so another
        // tenant's catalog is indistinguishable from a typo — which is the point.
        preview("billing-api", "production")
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.unregistered", contains("APPLICATION")));
    }

    @Test
    void aBlankNameIsRejectedRatherThanEvaluated() throws Exception {
        preview("payments-api", "   ").andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/deployment-checks/preview")
                        .param("application", "payments-api")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askingRecordsNothing() throws Exception {
        /*
         * The decision this story turned on. The checks console says it lists every time a
         * pipeline asked, and the refusal counts on the dashboard and on a restriction's
         * page are read from those rows — a person trying the form must not appear in
         * either. The audit trail is checked too, because the unregistered path is the one
         * that writes an entry when a pipeline asks.
         */
        givenFreeze(RestrictionLevel.HARD_FREEZE, "Black Friday Freeze");
        // A baseline rather than zero: issuing the API key in setup is itself audited.
        // Asserted non-zero so this cannot pass by counting nothing — an audit query that
        // always answered 0 would make the comparison below vacuous.
        int auditedBefore = auditEntries();
        assertThat(auditedBefore).isPositive();

        preview("payments-api", "production").andExpect(status().isOk());
        preview("checkout-web", "production").andExpect(status().isOk());

        assertThat(checksRecorded()).isZero();
        assertThat(auditEntries()).isEqualTo(auditedBefore);
    }

    @Test
    void thePipelineIsStillRecorded() throws Exception {
        // The other half of the one above: nothing here silenced the machine path, which
        // would be a far worse defect than the one it guards against.
        mockMvc.perform(post("/api/policy/evaluate")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DEPLOY\",\"application\":\"payments-api\","
                                + "\"environment\":\"production\"}"))
                .andExpect(status().isOk());

        assertThat(checksRecorded()).isEqualTo(1);
    }

    @Test
    void answersExactlyWhatThePipelineIsTold() throws Exception {
        /*
         * The reason this endpoint shares `PolicyService.decide` instead of re-deriving the
         * match: a product that disagreed with the gate about whether a freeze applies
         * would be worse than one that could not answer at all. Decision and wording are
         * both compared — agreeing on BLOCK while describing it differently is still two
         * answers.
         */
        givenFreeze(RestrictionLevel.HARD_FREEZE, "Black Friday Freeze");

        for (String[] pair : new String[][] {
                {"payments-api", "production"},   // blocked by the freeze
                {"payments-api", "staging"},      // an environment nobody registered
                {"checkout-web", "production"},   // an application nobody registered
        }) {
            JsonNode asked = MAPPER.readTree(preview(pair[0], pair[1])
                    .andReturn().getResponse().getContentAsString());
            JsonNode toldThePipeline = MAPPER.readTree(mockMvc.perform(post("/api/policy/evaluate")
                            .header("X-API-Key", apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"DEPLOY\",\"application\":\"" + pair[0]
                                    + "\",\"environment\":\"" + pair[1] + "\"}"))
                    .andReturn().getResponse().getContentAsString());

            assertThat(asked.get("decision")).isEqualTo(toldThePipeline.get("decision"));
            assertThat(asked.get("message")).isEqualTo(toldThePipeline.get("message"));
            assertThat(asked.get("unregistered")).isEqualTo(toldThePipeline.get("unregistered"));
            assertThat(asked.get("restrictions")).isEqualTo(toldThePipeline.get("restrictions"));
        }
    }
}
