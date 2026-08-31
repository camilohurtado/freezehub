package com.freezhub.organization;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.shared.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Reading and changing an organization's own settings (FZ-047). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class OrganizationSettingsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    private record Caller(Long organizationId, String token) {
    }

    private Caller callerWith(UserRole role) {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", role));
        return new Caller(organization.getId(), TestTokens.forSubject(jwtEncoder, subject));
    }

    private String leadTime(int minutes) {
        return "{\"startingSoonLeadTimeMinutes\":" + minutes + "}";
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/organization")).andExpect(status().isUnauthorized());
    }

    @Test
    void startsWithADaysNotice() throws Exception {
        Caller member = callerWith(UserRole.MEMBER);

        mockMvc.perform(get("/api/organization").header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(member.organizationId().intValue())))
                .andExpect(jsonPath("$.startingSoonLeadTimeMinutes", is(1440)));
    }

    @Test
    void letsAnAdministratorChangeTheLeadTime() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);

        mockMvc.perform(patch("/api/organization/settings")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leadTime(120)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startingSoonLeadTimeMinutes", is(120)));

        mockMvc.perform(get("/api/organization").header("Authorization", "Bearer " + admin.token()))
                .andExpect(jsonPath("$.startingSoonLeadTimeMinutes", is(120)));
    }

    @Test
    void doesNotLetAMemberChangeIt() throws Exception {
        // Reading is harmless; deciding how much warning everyone gets is not.
        Caller member = callerWith(UserRole.MEMBER);

        mockMvc.perform(patch("/api/organization/settings")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leadTime(120)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsALeadTimeOutsideTheSupportedRange() throws Exception {
        // Zero would fire the warning as the freeze begins, which ACTIVATED already
        // covers. 400 rather than a 500 from the database CHECK.
        Caller admin = callerWith(UserRole.ADMINISTRATOR);

        mockMvc.perform(patch("/api/organization/settings")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leadTime(0)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/organization/settings")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leadTime(60 * 24 * 31)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyEverAddressesTheCallersOwnOrganization() throws Exception {
        // No id in the path, so there is nothing to tamper with: two callers reading the
        // same endpoint see two different organizations.
        Caller ours = callerWith(UserRole.ADMINISTRATOR);
        Caller theirs = callerWith(UserRole.ADMINISTRATOR);

        mockMvc.perform(get("/api/organization").header("Authorization", "Bearer " + ours.token()))
                .andExpect(jsonPath("$.id", is(ours.organizationId().intValue())));
        mockMvc.perform(get("/api/organization").header("Authorization", "Bearer " + theirs.token()))
                .andExpect(jsonPath("$.id", is(theirs.organizationId().intValue())));
    }

}
