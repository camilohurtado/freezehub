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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The limit as a caller experiences it (FZ-087).
 *
 * <p>Covers the two limits applied by path: the unauthenticated endpoints, and the Stripe
 * webhook since {@code FZ-130}. The Policy API is limited inside its security chain rather
 * than by an interceptor, and is covered by {@code PolicyRateLimitTest}.
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
        "freezehub.rate-limit.unauthenticated.requests=3",
        "freezehub.rate-limit.unauthenticated.window=1m",
        "freezehub.rate-limit.unauthenticated.paths=/api/dev/token",
        // Its own budget, because it protects something else: Stripe bursts, and a number
        // that suits a signup form would refuse a customer's first subscription.
        "freezehub.rate-limit.stripe-webhook.requests=3",
        "freezehub.rate-limit.stripe-webhook.window=1m",
        "freezehub.stripe.secret-key=sk_test_not_a_real_key",
        "freezehub.stripe.webhook-secret=whsec_test_secret"
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
        // This limit is configured for /api/dev/token alone, and its budget is three. Ten
        // requests to an endpoint it does not cover are ten 200s: the paths are a list, not
        // a suggestion. (Since FZ-130 other endpoints have limits of their own — they are
        // separate counters, which is what this proves.)
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }

    /**
     * The Stripe webhook has a budget of its own (FZ-130).
     *
     * <p>Sent with a signature that cannot verify, so each request is refused — which is
     * the point. Verification is the work being protected: anyone can make FreezeHub do it,
     * because the endpoint has no credential in front of it by design.
     *
     * <p>Safe to enforce here in a way it is almost nowhere else, because Stripe retries a
     * non-2xx for up to three days. A refused delivery is delayed, not lost.
     */
    @Test
    void theStripeWebhookIsLimitedSeparately() throws Exception {
        for (int i = 0; i < 3; i++) {
            postStripeEvent("203.0.113.30").andExpect(status().isUnauthorized());
        }

        postStripeEvent("203.0.113.30")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // A different source still gets through: the counter is per caller, so one sender
        // hammering the endpoint cannot stop everybody else's subscriptions updating.
        postStripeEvent("203.0.113.31").andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions postStripeEvent(String from) throws Exception {
        return mockMvc.perform(post("/api/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "t=1,v1=not-a-valid-signature")
                .content("{\"id\":\"evt_test\",\"type\":\"customer.subscription.updated\"}")
                .with(raw -> {
                    raw.setRemoteAddr(from);
                    return raw;
                }));
    }
}
