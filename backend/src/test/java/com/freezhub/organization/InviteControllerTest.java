package com.freezhub.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class InviteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(post("/api/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserRequest("new@acme.test", null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsNonAdministrators() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme Inc"));
        userRepository.saveAndFlush(
                new User(organization.getId(), "member-subject", "member@acme.test", UserRole.MEMBER));
        String token = TestTokens.forSubject(jwtEncoder, "member-subject");

        mockMvc.perform(post("/api/invites")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserRequest("new@acme.test", null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorCanInviteAUserDefaultingToMemberRole() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme Inc"));
        userRepository.saveAndFlush(
                new User(organization.getId(), "admin-subject", "admin@acme.test", UserRole.ADMINISTRATOR));
        String token = TestTokens.forSubject(jwtEncoder, "admin-subject");

        mockMvc.perform(post("/api/invites")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserRequest("new@acme.test", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@acme.test"))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        assertThat(userRepository.existsByOrganizationIdAndEmail(organization.getId(), "new@acme.test")).isTrue();
    }

    @Test
    void rejectsInvitingAnEmailAlreadyInTheOrganization() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(new Organization("Acme Inc"));
        userRepository.saveAndFlush(
                new User(organization.getId(), "admin-subject-2", "admin2@acme.test", UserRole.ADMINISTRATOR));
        userRepository.saveAndFlush(
                new User(organization.getId(), "existing-subject", "existing@acme.test", UserRole.MEMBER));
        String token = TestTokens.forSubject(jwtEncoder, "admin-subject-2");

        mockMvc.perform(post("/api/invites")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteUserRequest("existing@acme.test", null))))
                .andExpect(status().isConflict());
    }

}
