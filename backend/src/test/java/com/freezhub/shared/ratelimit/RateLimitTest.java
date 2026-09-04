package com.freezhub.shared.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import com.freezhub.organization.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The limit as a caller experiences it (FZ-087).
 *
 * <p>Applied to {@code /api/dev/token} because it is the only unauthenticated endpoint
 * that exists yet — {@code /api/signup} and {@code /api/demo-requests} arrive with
 * {@code FZ-082} and {@code FZ-083}, and this story deliberately precedes them
 * ({@code OI-11}). It is also a fair subject: an endpoint that mints a credential without
 * one is exactly the shape being protected.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
@TestPropertySource(properties = {
        "freezehub.rate-limit.enabled=true",
        "freezehub.rate-limit.requests=3",
        "freezehub.rate-limit.window=1m",
        "freezehub.rate-limit.paths=/api/dev/token"
})
class RateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    private String email;

    @BeforeEach
    void setUp() {
        Organization organization =
                organizationRepository.saveAndFlush(new Organization("Northwind " + System.nanoTime()));
        email = "dev-" + System.nanoTime() + "@northwind.test";
        userRepository.saveAndFlush(new User(organization.getId(), UUID.randomUUID().toString(),
                email, UserRole.ADMINISTRATOR));
    }

    /**
     * A request from a given address.
     *
     * <p>Each test uses its own, because the limiter is one bean shared by every test in
     * this context: left on MockMvc's default 127.0.0.1 they would drain each other's
     * budget and fail depending on execution order.
     */
    private org.springframework.test.web.servlet.ResultActions mintToken(String from, String forwardedFor)
            throws Exception {
        var request = post("/api/dev/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")
                .with(raw -> {
                    raw.setRemoteAddr(from);
                    return raw;
                });
        if (forwardedFor != null) {
            request = request.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions mintToken(String from) throws Exception {
        return mintToken(from, null);
    }

    @Test
    void refusesWith429AfterTheLimitAndSaysWhenToRetry() throws Exception {
        for (int i = 0; i < 3; i++) {
            mintToken("203.0.113.10").andExpect(status().isOk());
        }

        mintToken("203.0.113.10")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.retryAfterSeconds").value(org.hamcrest.Matchers.greaterThan(0)))
                // Problem Details, like every other refusal in this API (FZ-061) — not a
                // second, hand-rolled error shape from a filter.
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/problem+json")));
    }

    @Test
    void aForgedForwardedHeaderDoesNotBuyAFreshBucket() throws Exception {
        // The evasion this must never allow: if the interceptor read X-Forwarded-For
        // itself, anyone could send a different one per request and never be limited —
        // or send somebody else's and fill their bucket instead.
        //
        // getRemoteAddr() is what makes this safe. Behind the load balancer,
        // server.forward-headers-strategy is what makes it return the real client, and
        // that is set only where a trusted proxy actually terminates the connection.
        //
        // Worth a test of its own because reading the header directly looks like a fix
        // for "everyone shares one bucket behind a proxy", and it is a hole.
        for (int i = 0; i < 3; i++) {
            mintToken("203.0.113.20", "10.0.0." + i).andExpect(status().isOk());
        }

        mintToken("203.0.113.20", "10.0.0.99").andExpect(status().isTooManyRequests());
    }

    @Test
    void doesNotTouchEndpointsOutsideItsPaths() throws Exception {
        // The limit is configured for /api/dev/token alone. An authenticated API already
        // requires a credential that can be revoked, which is a better answer than a
        // counter, and limiting one would throttle a customer's own pipeline.
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }
}
