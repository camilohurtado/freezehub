package com.freezhub.policy;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.apikey.ApiKeyService;
import com.freezhub.catalog.Application;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.catalog.Team;
import com.freezhub.catalog.TeamApplication;
import com.freezhub.catalog.TeamApplicationRepository;
import com.freezhub.catalog.TeamRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.ChangeRestrictionRepository;
import com.freezhub.restriction.ChangeRestrictionService;
import com.freezhub.restriction.RestrictionLevel;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Policy evaluation end to end, through the machine boundary (FZ-051). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class PolicyEvaluationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private TeamApplicationRepository teamApplicationRepository;

    @Autowired
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ChangeRestrictionService changeRestrictionService;

    private Long organizationId;
    private Long userId;
    private String apiKey;
    private Application paymentsApi;
    private Environment production;

    @BeforeEach
    void givenAnOrganizationWithACatalog() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        organizationId = organization.getId();

        String subject = "subject-" + System.nanoTime();
        User admin = userRepository.saveAndFlush(
                new User(organizationId, subject, subject + "@acme.test", UserRole.ADMINISTRATOR));
        userId = admin.getId();

        paymentsApi = applicationRepository.saveAndFlush(new Application(organizationId, "payments-api"));
        production = environmentRepository.saveAndFlush(new Environment(organizationId, "production"));

        apiKey = apiKeyService.create(organizationId, userId, "gitlab-ci").rawKey();
    }

    private ChangeRestriction givenRestriction(RestrictionLevel level, Instant startsAt, Instant endsAt,
                                               Set<Long> teamIds, Set<Long> applicationIds,
                                               Set<Long> environmentIds) {
        return changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                organizationId, "Freeze " + System.nanoTime(), null, "Revenue-critical period",
                level, startsAt, endsAt, userId, teamIds, applicationIds, environmentIds));
    }

    /** A HARD_FREEZE covering all of production, in force right now. */
    private ChangeRestriction givenProductionFreezeInForce() {
        Instant now = Instant.now();
        return givenRestriction(RestrictionLevel.HARD_FREEZE, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(1)), Set.of(), Set.of(), Set.of(production.getId()));
    }

    private ResultActions evaluate(String application, String environment) throws Exception {
        return evaluate("{\"action\":\"DEPLOY\",\"application\":\"" + application
                + "\",\"environment\":\"" + environment + "\"}");
    }

    private ResultActions evaluate(String body) throws Exception {
        return mockMvc.perform(post("/api/policy/evaluate")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void requiresAnApiKey() throws Exception {
        mockMvc.perform(post("/api/policy/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DEPLOY\",\"application\":\"payments-api\","
                                + "\"environment\":\"production\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsWhenNoRestrictionIsInForce() throws Exception {
        evaluate("payments-api", "production")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("ALLOW")))
                .andExpect(jsonPath("$.action", is("DEPLOY")))
                .andExpect(jsonPath("$.application", is("payments-api")))
                .andExpect(jsonPath("$.environment", is("production")))
                .andExpect(jsonPath("$.restrictions", is(empty())))
                .andExpect(jsonPath("$.unregistered", is(empty())))
                .andExpect(jsonPath("$.evaluatedAt").exists());
    }

    @Test
    void blocksOnAHardFreezeInForceAndSaysWhich() throws Exception {
        ChangeRestriction freeze = givenProductionFreezeInForce();

        evaluate("payments-api", "production")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.restrictions", hasSize(1)))
                .andExpect(jsonPath("$.restrictions[0].id", is(freeze.getId().intValue())))
                .andExpect(jsonPath("$.restrictions[0].level", is("HARD_FREEZE")))
                // The person reading a blocked pipeline needs to know why the freeze
                // exists, not merely that it does.
                .andExpect(jsonPath("$.restrictions[0].reason", is("Revenue-critical period")))
                .andExpect(jsonPath("$.message", containsString(freeze.getName())));
    }

    @Test
    void allowsWhenOnlyAdvisoryRestrictionsMatchButStillReportsThem() throws Exception {
        Instant now = Instant.now();
        givenRestriction(RestrictionLevel.ADVISORY, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(1)), Set.of(), Set.of(), Set.of(production.getId()));

        evaluate("payments-api", "production")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("ALLOW")))
                .andExpect(jsonPath("$.restrictions", hasSize(1)))
                .andExpect(jsonPath("$.restrictions[0].level", is("ADVISORY")));
    }

    @Test
    void blocksWhenAHardFreezeAndAnAdvisoryBothMatchAndListsBoth() throws Exception {
        Instant now = Instant.now();
        givenProductionFreezeInForce();
        givenRestriction(RestrictionLevel.ADVISORY, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(1)), Set.of(), Set.of(), Set.of(production.getId()));

        evaluate("payments-api", "production")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.restrictions", hasSize(2)));
    }

    @Test
    void decidesFromTheTimestampsRatherThanTheStatusColumn() throws Exception {
        // The whole point of `FZ-050`'s contract. This restriction's window has opened but
        // the lifecycle reconciler has not run, so its stored status is still SCHEDULED.
        // Trusting that column would allow a deployment in the middle of a freeze — a hole
        // that would appear only under load or just after a restart.
        ChangeRestriction freeze = givenProductionFreezeInForce();
        assertStatusIsStill(freeze, "SCHEDULED");

        evaluate("payments-api", "production")
                .andExpect(jsonPath("$.decision", is("BLOCK")));
    }

    private void assertStatusIsStill(ChangeRestriction restriction, String expected) {
        org.assertj.core.api.Assertions.assertThat(
                        changeRestrictionRepository.findById(restriction.getId()).orElseThrow()
                                .getStatus().name())
                .isEqualTo(expected);
    }

    @Test
    void ignoresACancelledRestriction() throws Exception {
        ChangeRestriction freeze = givenProductionFreezeInForce();
        // Cancelled through the real path rather than by poking the entity.
        changeRestrictionService.cancel(organizationId, freeze.getId());

        evaluate("payments-api", "production")
                .andExpect(jsonPath("$.decision", is("ALLOW")))
                .andExpect(jsonPath("$.restrictions", is(empty())));
    }

    @Test
    void ignoresARestrictionWhoseWindowHasPassed() throws Exception {
        Instant now = Instant.now();
        givenRestriction(RestrictionLevel.HARD_FREEZE, now.minus(Duration.ofDays(2)),
                now.minus(Duration.ofDays(1)), Set.of(), Set.of(), Set.of(production.getId()));

        evaluate("payments-api", "production").andExpect(jsonPath("$.decision", is("ALLOW")));
    }

    @Test
    void ignoresARestrictionThatHasNotStarted() throws Exception {
        Instant now = Instant.now();
        givenRestriction(RestrictionLevel.HARD_FREEZE, now.plus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(2)), Set.of(), Set.of(), Set.of(production.getId()));

        evaluate("payments-api", "production").andExpect(jsonPath("$.decision", is("ALLOW")));
    }

    @Test
    void respectsScopeSoAnUnrelatedFreezeDoesNotBlock() throws Exception {
        Environment staging = environmentRepository.saveAndFlush(new Environment(organizationId, "staging"));
        Instant now = Instant.now();
        givenRestriction(RestrictionLevel.HARD_FREEZE, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(1)), Set.of(), Set.of(), Set.of(staging.getId()));

        evaluate("payments-api", "production").andExpect(jsonPath("$.decision", is("ALLOW")));
    }

    @Test
    void appliesATeamScopedFreezeToThatTeamsApplications() throws Exception {
        Team payments = teamRepository.saveAndFlush(new Team(organizationId, "Payments"));
        teamApplicationRepository.saveAndFlush(new TeamApplication(payments.getId(), paymentsApi.getId()));
        Instant now = Instant.now();
        givenRestriction(RestrictionLevel.HARD_FREEZE, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(1)), Set.of(payments.getId()), Set.of(), Set.of());

        evaluate("payments-api", "production").andExpect(jsonPath("$.decision", is("BLOCK")));
    }

    @Test
    void doesNotApplyATeamScopedFreezeToAnApplicationOutsideThatTeam() throws Exception {
        Team platform = teamRepository.saveAndFlush(new Team(organizationId, "Platform"));
        Instant now = Instant.now();
        givenRestriction(RestrictionLevel.HARD_FREEZE, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(1)), Set.of(platform.getId()), Set.of(), Set.of());

        evaluate("payments-api", "production").andExpect(jsonPath("$.decision", is("ALLOW")));
    }

    @Test
    void blocksAnUnregisteredApplicationAndNamesIt() throws Exception {
        // The decision on `OI-8`: an unrecognised name blocks outright. Evaluating it
        // normally would tend toward ALLOW, because a name nobody registered matches no
        // scope list — so misspelling the environment would be a way to deploy straight
        // through a freeze with a legitimate-looking permission in the pipeline log.
        givenProductionFreezeInForce();

        evaluate("paymnets-api", "production")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.unregistered", contains("APPLICATION")))
                .andExpect(jsonPath("$.restrictions", is(empty())))
                .andExpect(jsonPath("$.message", containsString("paymnets-api")));
    }

    @Test
    void blocksAnUnregisteredEnvironmentAndNamesIt() throws Exception {
        // The original bypass: "prod" is not "production", so a freeze scoped to
        // production would not have matched.
        givenProductionFreezeInForce();

        evaluate("payments-api", "prod")
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.unregistered", contains("ENVIRONMENT")))
                .andExpect(jsonPath("$.message", containsString("prod")));
    }

    @Test
    void blocksAnUnregisteredNameEvenWhenNoFreezeExists() throws Exception {
        // The accepted cost of the decision: FreezeHub becomes a gate on catalog
        // completeness, so an application nobody has registered cannot deploy at all.
        evaluate("not-registered", "production")
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.unregistered", contains("APPLICATION")));
    }

    @Test
    void namesEveryUnregisteredDimensionRatherThanStoppingAtTheFirst() throws Exception {
        evaluate("nope", "nope")
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.unregistered", containsInAnyOrder("APPLICATION", "ENVIRONMENT")));
    }

    @Test
    void matchesNamesExactly() throws Exception {
        // Case-sensitive, because catalog uniqueness is: treating "Production" as
        // "production" here would make this endpoint disagree with the registry.
        evaluate("payments-api", "Production")
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.unregistered", contains("ENVIRONMENT")));
    }

    @Test
    void neverSeesAnotherOrganizationsRestrictions() throws Exception {
        Organization other = organizationRepository.saveAndFlush(new Organization("Other " + System.nanoTime()));
        String subject = "other-" + System.nanoTime();
        User otherAdmin = userRepository.saveAndFlush(
                new User(other.getId(), subject, subject + "@other.test", UserRole.ADMINISTRATOR));
        Environment theirProduction =
                environmentRepository.saveAndFlush(new Environment(other.getId(), "production"));
        Instant now = Instant.now();
        changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                other.getId(), "Their freeze", null, "Theirs", RestrictionLevel.HARD_FREEZE,
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), otherAdmin.getId(),
                Set.of(), Set.of(), Set.of(theirProduction.getId())));

        evaluate("payments-api", "production").andExpect(jsonPath("$.decision", is("ALLOW")));
    }

    @Test
    void treatsAnotherOrganizationsCatalogNamesAsUnregistered() throws Exception {
        Organization other = organizationRepository.saveAndFlush(new Organization("Other " + System.nanoTime()));
        applicationRepository.saveAndFlush(new Application(other.getId(), "their-api"));

        evaluate("their-api", "production")
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.unregistered", contains("APPLICATION")));
    }

    @Test
    void rejectsAnUnsupportedAction() throws Exception {
        evaluate("{\"action\":\"DESTROY\",\"application\":\"payments-api\",\"environment\":\"production\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAMissingApplicationOrEnvironment() throws Exception {
        evaluate("{\"action\":\"DEPLOY\",\"environment\":\"production\"}")
                .andExpect(status().isBadRequest());
        evaluate("{\"action\":\"DEPLOY\",\"application\":\"payments-api\"}")
                .andExpect(status().isBadRequest());
        evaluate("{\"application\":\"payments-api\",\"environment\":\"production\"}")
                .andExpect(status().isBadRequest());
        evaluate("{\"action\":\"DEPLOY\",\"application\":\"  \",\"environment\":\"production\"}")
                .andExpect(status().isBadRequest());
    }

}
