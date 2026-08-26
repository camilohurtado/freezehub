package com.freezhub.shared.security;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class DevSignInControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Organization newOrganization() {
        return organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
    }

    private String body(String email) throws Exception {
        return objectMapper.writeValueAsString(new DevSignInController.DevTokenRequest(email));
    }

    @Test
    void issuesATokenForAnExistingUser() throws Exception {
        Organization organization = newOrganization();
        String email = "signin-" + System.nanoTime() + "@acme.test";
        User user = userRepository.saveAndFlush(new User(
                organization.getId(), "subject-" + System.nanoTime(), email, UserRole.ADMINISTRATOR));

        mockMvc.perform(post("/api/dev/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.userId", is(user.getId().intValue())))
                .andExpect(jsonPath("$.organizationId", is(organization.getId().intValue())))
                .andExpect(jsonPath("$.email", is(email)))
                .andExpect(jsonPath("$.role", is("ADMINISTRATOR")));
    }

    @Test
    void theIssuedTokenActuallyAuthenticates() throws Exception {
        // End to end: the point of the endpoint is a token the rest of the API accepts.
        Organization organization = newOrganization();
        String email = "usable-" + System.nanoTime() + "@acme.test";
        User user = userRepository.saveAndFlush(new User(
                organization.getId(), "subject-" + System.nanoTime(), email, UserRole.MEMBER));

        String response = mockMvc.perform(post("/api/dev/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).get("token").asText();

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(user.getId().intValue())))
                .andExpect(jsonPath("$.organizationId", is(organization.getId().intValue())));
    }

    @Test
    void doesNotRequireAuthenticationItself() throws Exception {
        // Sign-in has to be reachable without a token; anything other than 401 proves the
        // dev filter chain is in front of the main one.
        mockMvc.perform(post("/api/dev/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("nobody-" + System.nanoTime() + "@acme.test")))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesToMintATokenForAnIdentityThatDoesNotExist() throws Exception {
        // A sign-in shortcut, not a way to conjure identities.
        mockMvc.perform(post("/api/dev/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ghost-" + System.nanoTime() + "@acme.test")))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesAnEmailThatExistsInMoreThanOneOrganization() throws Exception {
        // Email is unique per organization, not globally - guessing would sign the
        // developer into an arbitrary tenant.
        String sharedEmail = "shared-" + System.nanoTime() + "@acme.test";
        Organization first = newOrganization();
        Organization second = newOrganization();
        userRepository.saveAndFlush(new User(
                first.getId(), "subject-a-" + System.nanoTime(), sharedEmail, UserRole.MEMBER));
        userRepository.saveAndFlush(new User(
                second.getId(), "subject-b-" + System.nanoTime(), sharedEmail, UserRole.ADMINISTRATOR));

        mockMvc.perform(post("/api/dev/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sharedEmail)))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsABlankEmail() throws Exception {
        mockMvc.perform(post("/api/dev/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest());
    }

}
