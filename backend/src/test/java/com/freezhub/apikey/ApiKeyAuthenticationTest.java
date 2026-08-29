package com.freezhub.apikey;

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
import com.freezhub.shared.security.ApiKeyPrincipal;
import com.freezhub.shared.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The machine-facing chain (FZ-052): who gets through {@code /api/policy/**}, and as which
 * organization.
 *
 * <p>Exercised against a stand-in endpoint because `FZ-051` has not added the real one
 * yet. That is the point of the story order — the credential and the door exist before the
 * thing behind the door, so the deployment gate never ships unauthenticated (`OI-9`).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import({ContainersConfig.class, ApiKeyAuthenticationTest.MachineEndpoint.class})
class ApiKeyAuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    private record Issued(Long organizationId, Long keyId, String rawKey, String jwt) {
    }

    private Issued givenAKey() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        User admin = userRepository.saveAndFlush(new User(
                organization.getId(), subject, subject + "@acme.test", UserRole.ADMINISTRATOR));

        ApiKeyService.IssuedApiKey issued =
                apiKeyService.create(organization.getId(), admin.getId(), "gitlab-ci");

        return new Issued(organization.getId(), issued.apiKey().getId(), issued.rawKey(),
                TestTokens.forSubject(jwtEncoder, subject));
    }

    @Test
    void authenticatesAValidKeyAndResolvesItsOrganization() throws Exception {
        // The organization comes from the stored credential. Nothing in the request says
        // which tenant this is, and nothing in the request could.
        Issued issued = givenAKey();

        mockMvc.perform(get("/api/policy/ping").header("X-API-Key", issued.rawKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId", is(issued.organizationId().intValue())))
                .andExpect(jsonPath("$.name", is("gitlab-ci")));
    }

    @Test
    void rejectsARequestWithNoKey() throws Exception {
        mockMvc.perform(get("/api/policy/ping")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnUnknownKey() throws Exception {
        mockMvc.perform(get("/api/policy/ping").header("X-API-Key", "fzh_not-a-real-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAKeyThatIsNotEvenKeyShaped() throws Exception {
        // Hashing arbitrary input must simply fail to match, not blow up.
        mockMvc.perform(get("/api/policy/ping").header("X-API-Key", ""))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/policy/ping").header("X-API-Key", "  "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsARevokedKey() throws Exception {
        // Revocation has to take effect on the next request; a credential that keeps
        // working after being withdrawn is not revocation.
        Issued issued = givenAKey();
        mockMvc.perform(get("/api/policy/ping").header("X-API-Key", issued.rawKey()))
                .andExpect(status().isOk());

        apiKeyService.revoke(issued.organizationId(), issued.keyId());

        mockMvc.perform(get("/api/policy/ping").header("X-API-Key", issued.rawKey()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doesNotAcceptAHumanTokenOnTheMachineChain() throws Exception {
        // 06-security.md keeps the two mechanisms distinct: a browser session must not be
        // able to reach the machine boundary just because it happens to be signed in.
        Issued issued = givenAKey();

        mockMvc.perform(get("/api/policy/ping").header("Authorization", "Bearer " + issued.jwt()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doesNotAcceptAnApiKeyOnTheHumanApi() throws Exception {
        // The other direction, and the more important one: a key leaked from CI must not
        // be able to read or change the organization's data. It opens one door only.
        Issued issued = givenAKey();

        mockMvc.perform(get("/api/teams").header("X-API-Key", issued.rawKey()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/api-keys").header("X-API-Key", issued.rawKey()))
                .andExpect(status().isUnauthorized());
    }

    /** Stands in for the Policy API until `FZ-051` provides the real endpoint. */
    @TestConfiguration
    static class MachineEndpoint {

        @Bean
        PingController pingController() {
            return new PingController();
        }
    }

    @RestController
    static class PingController {

        @GetMapping("/api/policy/ping")
        ApiKeyPrincipal ping(@AuthenticationPrincipal ApiKeyPrincipal caller) {
            return caller;
        }
    }

}
