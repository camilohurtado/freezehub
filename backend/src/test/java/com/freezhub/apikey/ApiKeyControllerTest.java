package com.freezhub.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Issuing, listing and revoking machine credentials (FZ-052). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class ApiKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObjectMapper objectMapper;

    private record Caller(Long organizationId, Long userId, String token) {
    }

    private Caller callerWith(UserRole role) {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        User user = userRepository.saveAndFlush(
                new User(organization.getId(), subject, subject + "@acme.test", role));
        return new Caller(organization.getId(), user.getId(), TestTokens.forSubject(jwtEncoder, subject));
    }

    private JsonNode createKey(Caller caller, String name) throws Exception {
        String body = mockMvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + caller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/api-keys")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsNonAdministrators() throws Exception {
        // A key authenticates as the whole organization, so issuing one is a decision
        // about who may act on its behalf — same bar as invite and integrations.
        Caller member = callerWith(UserRole.MEMBER);

        mockMvc.perform(get("/api/api-keys").header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void issuesAKeyAndReturnsTheRawSecretOnce() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);

        JsonNode created = createKey(admin, "gitlab-ci");

        assertThat(created.get("key").asText()).startsWith("fzh_");
        assertThat(created.get("name").asText()).isEqualTo("gitlab-ci");
        assertThat(created.get("keyPrefix").asText())
                .isEqualTo(created.get("key").asText().substring(0, 10));
        assertThat(created.get("createdBy").asLong()).isEqualTo(admin.userId());
    }

    @Test
    void neverReturnsTheRawKeyAgain() throws Exception {
        // The whole point of the credential: after creation it exists only wherever the
        // caller put it. A listing that returned it would defeat storing a hash at all.
        Caller admin = callerWith(UserRole.ADMINISTRATOR);
        String rawKey = createKey(admin, "gitlab-ci").get("key").asText();

        mockMvc.perform(get("/api/api-keys").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("gitlab-ci")))
                .andExpect(jsonPath("$[0].revoked", is(false)))
                .andExpect(jsonPath("$[0].revokedAt", is(nullValue())))
                .andExpect(content().string(not(containsString(rawKey))));
    }

    @Test
    void storesOnlyAHashOfTheKey() throws Exception {
        // 06-security.md: raw API key secrets are never stored after creation. Asserted
        // against the persisted row, not the API, so no future response shape can hide it.
        Caller admin = callerWith(UserRole.ADMINISTRATOR);
        JsonNode created = createKey(admin, "gitlab-ci");
        String rawKey = created.get("key").asText();

        ApiKey stored = apiKeyRepository.findById(created.get("id").asLong()).orElseThrow();

        assertThat(stored.getTokenHash()).isNotEqualTo(rawKey).doesNotContain(rawKey);
        assertThat(stored.getKeyPrefix()).isNotEqualTo(rawKey);
        assertThat(apiKeyRepository.findByTokenHash(rawKey)).isEmpty();
    }

    @Test
    void listsOnlyTheCallersOwnKeys() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);
        Caller otherOrganization = callerWith(UserRole.ADMINISTRATOR);
        createKey(admin, "ours");
        createKey(otherOrganization, "theirs");

        mockMvc.perform(get("/api/api-keys").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name", is("ours")));
    }

    @Test
    void revokesAKey() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);
        long keyId = createKey(admin, "gitlab-ci").get("id").asLong();

        mockMvc.perform(post("/api/api-keys/" + keyId + "/revoke")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked", is(true)))
                .andExpect(jsonPath("$.revokedAt", is(not(nullValue()))));
    }

    @Test
    void rejectsRevokingAnAlreadyRevokedKey() throws Exception {
        // Not idempotent on purpose, matching restriction cancellation: a second revoke
        // means the caller believed the key was still live, which is worth telling them.
        Caller admin = callerWith(UserRole.ADMINISTRATOR);
        long keyId = createKey(admin, "gitlab-ci").get("id").asLong();
        apiKeyService.revoke(admin.organizationId(), keyId);

        mockMvc.perform(post("/api/api-keys/" + keyId + "/revoke")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already revoked")));
    }

    @Test
    void treatsAnotherOrganizationsKeyAsUnknown() throws Exception {
        // 404, never 403: cross-tenant existence is never revealed.
        Caller admin = callerWith(UserRole.ADMINISTRATOR);
        Caller otherOrganization = callerWith(UserRole.ADMINISTRATOR);
        long theirKeyId = createKey(otherOrganization, "theirs").get("id").asLong();

        mockMvc.perform(post("/api/api-keys/" + theirKeyId + "/revoke")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isNotFound());

        assertThat(apiKeyRepository.findById(theirKeyId).orElseThrow().isRevoked()).isFalse();
    }

    @Test
    void requiresAName() throws Exception {
        Caller admin = callerWith(UserRole.ADMINISTRATOR);

        mockMvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

}
