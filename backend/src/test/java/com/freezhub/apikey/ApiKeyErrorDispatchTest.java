package com.freezhub.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import com.freezhub.audit.AuditActor;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * An error raised behind the machine chain must keep its own status (FZ-052).
 *
 * <p>Runs against a real servlet container rather than MockMvc, and that is the whole
 * point: only a real container forwards a failed request to {@code /error}. That forward
 * is re-filtered by Spring Security, {@code /error} does not match
 * {@code /api/policy/**}, and so an authenticated machine request's 400 or 404 used to
 * fall through to the human chain and come back as {@code 401} — telling a pipeline its
 * credential was rejected when the credential was fine.
 *
 * <p>Found by exercising the running application, not by a test; MockMvc resolves handler
 * exceptions in place and never performs the forward, so no controller test could have
 * shown it. This one can.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class ApiKeyErrorDispatchTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    /** The actor a controller would have built from the signed-in administrator. */
    private AuditActor actorFor(User user) {
        return AuditActor.of(new AuthenticatedUser(
                user.getId(), user.getOrganizationId(), user.getEmail(), user.getRole()));
    }

    private String givenAKey() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Acme " + System.nanoTime()));
        String subject = "subject-" + System.nanoTime();
        User admin = userRepository.saveAndFlush(new User(
                organization.getId(), subject, subject + "@acme.test", UserRole.ADMINISTRATOR));

        return apiKeyService.create(organization.getId(), actorFor(admin), "gitlab-ci").rawKey();
    }

    private ResponseEntity<String> policyRequest(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }

        return restTemplate.exchange("/api/policy/does-not-exist", HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
    }

    @Test
    void doesNotTurnAnErrorBehindAValidKeyIntoAnAuthenticationFailure() {
        // 404 because `FZ-051` has not added the endpoint yet — the status does not
        // matter, only that it is the request's own outcome and not a 401. A pipeline
        // debugging a 401 goes looking at its credential, which would be the wrong place.
        assertThat(policyRequest(givenAKey()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void stillRejectsAMissingCredentialOutright() {
        // The counterpart: permitting the error dispatch must not have opened the chain.
        // A request with no key is refused on the way in and never reaches an error.
        assertThat(policyRequest(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(policyRequest("fzh_not-a-real-key").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

}
