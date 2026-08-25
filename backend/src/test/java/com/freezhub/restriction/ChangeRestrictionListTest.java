package com.freezhub.restriction;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class ChangeRestrictionListTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChangeRestrictionRepository changeRestrictionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /** Persists a SCHEDULED restriction starting {@code daysFromNow}. */
    private ChangeRestriction givenRestriction(Organization organization, Caller caller, String name,
                                               long daysFromNow) {
        Instant startsAt = Instant.now().plus(daysFromNow, ChronoUnit.DAYS);
        return changeRestrictionRepository.saveAndFlush(new ChangeRestriction(
                organization.getId(), name, null, "Reason", RestrictionLevel.HARD_FREEZE,
                startsAt, startsAt.plus(1, ChronoUnit.DAYS), caller.userId(),
                Set.of(), Set.of(), Set.of()));
    }

    /**
     * Forces a status directly in SQL. Lifecycle transitions are FZ-025 and intentionally
     * have no production code yet, but the filter still has to be provable against the
     * statuses it will eventually see.
     */
    private void forceStatus(ChangeRestriction restriction, RestrictionStatus status) {
        jdbcTemplate.update("UPDATE change_restriction SET status = ? WHERE id = ?",
                status.name(), restriction.getId());
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/restrictions")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsAnEmptyListForAnOrganizationWithNoRestrictions() throws Exception {
        Caller caller = callerFor(newOrganization());

        mockMvc.perform(get("/api/restrictions").header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listsOnlyRestrictionsOfTheCallersOrganization() throws Exception {
        Organization orgA = newOrganization();
        Organization orgB = newOrganization();
        Caller callerA = callerFor(orgA);
        Caller callerB = callerFor(orgB);

        givenRestriction(orgA, callerA, "Org A Freeze", 1);
        givenRestriction(orgB, callerB, "Org B Freeze", 1);

        mockMvc.perform(get("/api/restrictions").header("Authorization", "Bearer " + callerA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Org A Freeze")));

        mockMvc.perform(get("/api/restrictions").header("Authorization", "Bearer " + callerB.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Org B Freeze")));
    }

    @Test
    void ordersBySoonestStartFirst() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);

        // Persisted out of order on purpose.
        givenRestriction(organization, caller, "Third", 30);
        givenRestriction(organization, caller, "First", 1);
        givenRestriction(organization, caller, "Second", 10);

        mockMvc.perform(get("/api/restrictions").header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("First", "Second", "Third")));
    }

    @Test
    void omittingTheStatusFilterReturnsEveryStatus() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);

        givenRestriction(organization, caller, "Scheduled one", 1);
        forceStatus(givenRestriction(organization, caller, "Active one", 2), RestrictionStatus.ACTIVE);
        forceStatus(givenRestriction(organization, caller, "Cancelled one", 3), RestrictionStatus.CANCELLED);

        mockMvc.perform(get("/api/restrictions").header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void filtersByASingleStatus() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);

        givenRestriction(organization, caller, "Scheduled one", 1);
        forceStatus(givenRestriction(organization, caller, "Active one", 2), RestrictionStatus.ACTIVE);

        mockMvc.perform(get("/api/restrictions")
                        .param("status", "ACTIVE")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("Active one")))
                .andExpect(jsonPath("$[0].status", is("ACTIVE")));
    }

    @Test
    void filtersBySeveralStatusesAtOnce() throws Exception {
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);

        givenRestriction(organization, caller, "Scheduled one", 1);
        forceStatus(givenRestriction(organization, caller, "Active one", 2), RestrictionStatus.ACTIVE);
        forceStatus(givenRestriction(organization, caller, "Completed one", 3), RestrictionStatus.COMPLETED);
        forceStatus(givenRestriction(organization, caller, "Cancelled one", 4), RestrictionStatus.CANCELLED);

        // The dashboard question: "what is active or upcoming?"
        mockMvc.perform(get("/api/restrictions")
                        .param("status", "SCHEDULED")
                        .param("status", "ACTIVE")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", contains("Scheduled one", "Active one")))
                .andExpect(jsonPath("$[*].status", everyItem(anyOf(is("SCHEDULED"), is("ACTIVE")))));
    }

    @Test
    void rejectsAnUnrecognisedStatusValue() throws Exception {
        Caller caller = callerFor(newOrganization());

        mockMvc.perform(get("/api/restrictions")
                        .param("status", "NOT_A_STATUS")
                        .header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void summaryOmitsScope() throws Exception {
        // Scope belongs to the detail representation (FZ-022), not the list.
        Organization organization = newOrganization();
        Caller caller = callerFor(organization);
        givenRestriction(organization, caller, "Some freeze", 1);

        mockMvc.perform(get("/api/restrictions").header("Authorization", "Bearer " + caller.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scope").doesNotExist())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].level").exists())
                .andExpect(jsonPath("$[0].startsAt").exists());
    }

}
