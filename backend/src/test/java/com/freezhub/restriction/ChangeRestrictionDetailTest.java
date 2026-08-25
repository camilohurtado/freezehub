package com.freezhub.restriction;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.catalog.Application;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.catalog.Team;
import com.freezhub.catalog.TeamRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.TestTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class ChangeRestrictionDetailTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

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
    private ChangeRestrictionRepository changeRestrictionRepository;

    private Organization newOrganization() {
        return organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
    }

    private record Caller(String token, Long userId) {
    }

    private Caller callerFor(Organization organization) {
        String subject = "subject-" + System.nanoTime();
        User user = userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", UserRole.MEMBER));
        return new Caller(TestTokens.forSubject(jwtEncoder, subject), user.getId());
    }

    private ChangeRestriction givenRestriction(Organization organization, Caller caller,
                                               Set<Long> teamIds, Set<Long> applicationIds,
                                               Set<Long> environmentIds) {
        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
        return changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                organization.getId(), "Black Friday Freeze", "No production deploys", "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE, startsAt, startsAt.plus(5, ChronoUnit.DAYS), caller.userId(),
                teamIds, applicationIds, environmentIds));
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/restrictions/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheRestrictionWithItsFullScope() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);

        Long teamId = teamRepository.saveAndFlush(new Team(organization.getId(), "Payments")).getId();
        Long applicationId =
                applicationRepository.saveAndFlush(new Application(organization.getId(), "payments-api")).getId();
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        ChangeRestriction restriction =
                givenRestriction(organization, caller, Set.of(teamId), Set.of(applicationId), Set.of(environmentId));

        // Also the regression guard for LazyInitializationException: the scope collections
        // are LAZY and open-in-view is disabled, so this only passes if the service
        // initialises them inside its transaction.
        mockMvc.perform(get("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(restriction.getId().intValue())))
                .andExpect(jsonPath("$.name", is("Black Friday Freeze")))
                .andExpect(jsonPath("$.description", is("No production deploys")))
                .andExpect(jsonPath("$.reason", is("Revenue-critical period")))
                .andExpect(jsonPath("$.type", is("DEPLOYMENT_FREEZE")))
                .andExpect(jsonPath("$.level", is("HARD_FREEZE")))
                .andExpect(jsonPath("$.status", is("SCHEDULED")))
                .andExpect(jsonPath("$.createdBy", is(caller.userId().intValue())))
                .andExpect(jsonPath("$.startsAt").exists())
                .andExpect(jsonPath("$.endsAt").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.scope.teamIds", contains(teamId.intValue())))
                .andExpect(jsonPath("$.scope.applicationIds", contains(applicationId.intValue())))
                .andExpect(jsonPath("$.scope.environmentIds", contains(environmentId.intValue())));
    }

    @Test
    void returnsEmptyArraysForUnusedScopeDimensions() throws Exception {
        // The "freeze all of production" shape: the wildcard dimensions come back empty,
        // not absent or null.
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        Long environmentId =
                environmentRepository.saveAndFlush(new Environment(organization.getId(), "production")).getId();

        ChangeRestriction restriction =
                givenRestriction(organization, caller, Set.of(), Set.of(), Set.of(environmentId));

        mockMvc.perform(get("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.teamIds", is(empty())))
                .andExpect(jsonPath("$.scope.applicationIds", is(empty())))
                .andExpect(jsonPath("$.scope.environmentIds", contains(environmentId.intValue())));
    }

    @Test
    void returnsMultipleScopeTargetsWithinADimension() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);

        Long firstApp =
                applicationRepository.saveAndFlush(new Application(organization.getId(), "payments-api")).getId();
        Long secondApp =
                applicationRepository.saveAndFlush(new Application(organization.getId(), "checkout-api")).getId();

        ChangeRestriction restriction =
                givenRestriction(organization, caller, Set.of(), Set.of(firstApp, secondApp), Set.of());

        mockMvc.perform(get("/api/restrictions/" + restriction.getId())
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.applicationIds",
                        containsInAnyOrder(firstApp.intValue(), secondApp.intValue())));
    }

    @Test
    void returnsNotFoundForAnUnknownId() throws Exception {
        Caller caller = callerFor(newOrganization());

        mockMvc.perform(get("/api/restrictions/999999").header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsNotFoundForARestrictionOwnedByAnotherOrganization() throws Exception {
        Organization orgA = newOrganization();
        Organization orgB = newOrganization();
        Caller callerA = callerFor(orgA);
        Caller callerB = callerFor(orgB);

        ChangeRestriction restrictionOfA = givenRestriction(orgA, callerA, Set.of(), Set.of(), Set.of());

        // 404 rather than 403: the existence of another tenant's restriction is not revealed.
        mockMvc.perform(get("/api/restrictions/" + restrictionOfA.getId())
                        .header("Authorization", "Bearer " + callerB.token()))
                .andExpect(status().isNotFound());
    }

}
