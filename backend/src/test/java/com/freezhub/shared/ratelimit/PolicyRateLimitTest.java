package com.freezhub.shared.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.apikey.ApiKeyService;
import com.freezhub.audit.AuditActor;
import com.freezhub.catalog.Application;
import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.Environment;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import com.freezhub.shared.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The Policy API's two limits, as a caller experiences them (FZ-130, `OI-27`).
 *
 * <p>This endpoint is limited in the security chain rather than by the interceptor the
 * unauthenticated endpoints use, because a request with no usable key is refused before the
 * DispatcherServlet — so an interceptor would count only the calls that succeeded, and the
 * half with no credential behind it would stay unmetered.
 *
 * <p><strong>The test that matters most is the one about not refusing.</strong>
 * {@code freeze-check.sh} fails closed by default (`FREEZEHUB_ON_ERROR=block`, `FZ-053`), so a wrong {@code 429} does not slow a
 * deployment, it stops it. A limiter that can be filled on a valid pipeline's behalf — by
 * anyone sharing its source address, which behind a corporate NAT means everyone — would
 * have reproduced exactly the outage this product exists to schedule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
@TestPropertySource(properties = {
        "freezehub.rate-limit.enabled=true",
        "freezehub.rate-limit.policy.failures.requests=3",
        "freezehub.rate-limit.policy.failures.window=1m",
        "freezehub.rate-limit.policy.per-key.requests=3",
        "freezehub.rate-limit.policy.per-key.window=1m"
})
class PolicyRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    private String gitlabKey;
    private String jenkinsKey;

    @BeforeEach
    void givenAnOrganizationWithTwoPipelines() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Northwind " + System.nanoTime()));
        Long organizationId = organization.getId();

        String subject = "subject-" + System.nanoTime();
        User admin = userRepository.saveAndFlush(
                new User(organizationId, subject, subject + "@northwind.test", UserRole.ADMINISTRATOR));
        AuditActor actor = AuditActor.of(new AuthenticatedUser(
                admin.getId(), organizationId, admin.getEmail(), admin.getRole()));

        applicationRepository.saveAndFlush(new Application(organizationId, "payments-api"));
        environmentRepository.saveAndFlush(new Environment(organizationId, "production"));

        gitlabKey = apiKeyService.create(organizationId, actor, "gitlab-ci").rawKey();
        jenkinsKey = apiKeyService.create(organizationId, actor, "jenkins").rawKey();
    }

    /**
     * A request from a given address, with or without a credential.
     *
     * <p>Each test uses its own address, because the limiters are beans shared by every
     * test in this context: left on MockMvc's default 127.0.0.1 they would drain each
     * other's budget and pass or fail depending on execution order.
     */
    private ResultActions evaluate(String from, String apiKey) throws Exception {
        var request = post("/api/policy/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"DEPLOY\",\"application\":\"payments-api\","
                        + "\"environment\":\"production\"}")
                .with(raw -> {
                    raw.setRemoteAddr(from);
                    return raw;
                });
        if (apiKey != null) {
            request = request.header("X-API-Key", apiKey);
        }
        return mockMvc.perform(request);
    }

    @Test
    void refusesRepeatedAttemptsWithoutAUsableKey() throws Exception {
        for (int i = 0; i < 3; i++) {
            evaluate("203.0.113.40", null).andExpect(status().isUnauthorized());
        }

        evaluate("203.0.113.40", null)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.retryAfterSeconds").value(org.hamcrest.Matchers.greaterThan(0)))
                // Problem Details, like every other refusal in this API (FZ-061). The
                // refusal is raised in a filter, where the advice does not apply by
                // itself — rendering it by hand there is how an API acquires a second
                // error shape that no client knows about.
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/problem+json")));
    }

    @Test
    void aValidKeyIsNotRefusedByTheFailedAttemptsAroundIt() throws Exception {
        // Everything here comes from one address, which is the realistic case: a corporate
        // NAT, a shared runner pool, a single egress IP for an entire customer.
        for (int i = 0; i < 3; i++) {
            evaluate("203.0.113.41", null).andExpect(status().isUnauthorized());
        }
        evaluate("203.0.113.41", null).andExpect(status().isTooManyRequests());

        // The deployment still goes ahead. If the two limits shared a counter — or if the
        // authenticated one were keyed by address — this would be a 429, and every pipeline
        // behind that address would stop deploying because one of them has a stale key.
        evaluate("203.0.113.41", gitlabKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"));
    }

    @Test
    void limitsOneKeyWithoutTouchingAnother() throws Exception {
        for (int i = 0; i < 3; i++) {
            evaluate("203.0.113.42", gitlabKey).andExpect(status().isOk());
        }

        evaluate("203.0.113.42", gitlabKey)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Same organization, same address, different credential: a runaway pipeline spends
        // its own budget and nobody else's.
        evaluate("203.0.113.42", jenkinsKey).andExpect(status().isOk());
    }
}
