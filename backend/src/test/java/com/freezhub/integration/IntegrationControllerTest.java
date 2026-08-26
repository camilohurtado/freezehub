package com.freezhub.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
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
class IntegrationControllerTest {

    private static final String SLACK_WEBHOOK =
            "https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IntegrationRepository integrationRepository;

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

    private Integration givenSlack(Long organizationId) {
        return integrationRepository.saveAndFlush(new Integration(
                organizationId, IntegrationType.SLACK, "{\"webhookUrl\":\"" + SLACK_WEBHOOK + "\"}"));
    }

    private String slackBody() {
        return "{\"type\":\"SLACK\",\"config\":\"{\\\"webhookUrl\\\":\\\"" + SLACK_WEBHOOK + "\\\"}\"}";
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/integrations")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsNonAdministrators() throws Exception {
        // A destination decides who hears about a freeze, and its config can hold a
        // credential — same bar as invite (06-security.md).
        Caller member = callerWith(UserRole.MEMBER);

        mockMvc.perform(get("/api/integrations").header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsAndListsAnIntegration() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);

        mockMvc.perform(post("/api/integrations")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slackBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("SLACK")))
                .andExpect(jsonPath("$.enabled", is(true)));

        mockMvc.perform(get("/api/integrations").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type", is("SLACK")));
    }

    @Test
    void neverReturnsTheStoredCredential() throws Exception {
        // A Slack webhook URL is a bearer credential: anyone holding it can post to that
        // channel. Reading it back out of the API would hand it to any admin session,
        // browser history or log that saw the response.
        Caller admin = callerWith(UserRole.ADMINISTRATOR);
        givenSlack(admin.organizationId());

        mockMvc.perform(get("/api/integrations").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("XXXXXXXXXXXXXXXXXXXXXXXX"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("webhookUrl"))))
                // Identifiable without being reusable.
                .andExpect(jsonPath("$[0].summary", is("hooks.slack.com")));
    }

    @Test
    void rejectsASlackConfigWithoutAWebhookUrl() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);

        mockMvc.perform(post("/api/integrations")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SLACK\",\"config\":\"{}\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsANonHttpsDestination() throws Exception {
        // These carry credentials and freeze announcements.
        Caller admin = callerWith(UserRole.ADMINISTRATOR);

        mockMvc.perform(post("/api/integrations")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"WEBHOOK\",\"config\":\"{\\\"url\\\":\\\"http://insecure.test/hook\\\"}\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedConfigJson() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);

        mockMvc.perform(post("/api/integrations")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SLACK\",\"config\":\"not json\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnEmailIntegrationWithNoRecipients() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);

        mockMvc.perform(post("/api/integrations")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EMAIL\",\"config\":\"{\\\"recipients\\\":[]}\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disablesWithoutLosingConfiguration() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);
        Integration slack = givenSlack(admin.organizationId());

        mockMvc.perform(patch("/api/integrations/" + slack.getId())
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)));

        // Still there, so it can be switched back on without re-entering the credential.
        assertThat(integrationRepository.findById(slack.getId()).orElseThrow().getConfig())
                .contains("hooks.slack.com");
    }

    @Test
    void deletesAnIntegration() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);
        Integration slack = givenSlack(admin.organizationId());

        mockMvc.perform(delete("/api/integrations/" + slack.getId())
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isNoContent());

        assertThat(integrationRepository.findById(slack.getId())).isEmpty();
    }

    @Test
    void doesNotExposeAnotherOrganizationsIntegration() throws Exception {
        Caller owner = callerWith(UserRole.ADMINISTRATOR);
        Caller neighbour = callerWith(UserRole.ADMINISTRATOR);
        Integration slack = givenSlack(owner.organizationId());

        mockMvc.perform(get("/api/integrations").header("Authorization", "Bearer " + neighbour.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));

        mockMvc.perform(delete("/api/integrations/" + slack.getId())
                        .header("Authorization", "Bearer " + neighbour.token()))
                .andExpect(status().isNotFound());

        assertThat(integrationRepository.findById(slack.getId())).isPresent();
    }

}
