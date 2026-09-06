package com.freezhub.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freezhub.ContainersConfig;
import com.freezhub.organization.Organization;
import com.freezhub.organization.OrganizationRepository;
import com.freezhub.subscription.Plan;
import com.freezhub.subscription.Subscription;
import com.freezhub.subscription.SubscriptionRepository;
import com.freezhub.subscription.SubscriptionStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * The webhook is the only thing that may change entitlement (FZ-084).
 *
 * <p>Signatures are built here rather than mocked: the whole security of this endpoint is
 * the HMAC, and a test that stubs it out would prove nothing about the thing being
 * protected.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ContainersConfig.class)
@TestPropertySource(properties = {
        "freezehub.stripe.secret-key=sk_test_not_a_real_key",
        "freezehub.stripe.webhook-secret=whsec_test_secret",
        "freezehub.rate-limit.enabled=false"
})
class StripeWebhookTest {

    private static final String WEBHOOK_SECRET = "whsec_test_secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private SubscriptionRepository subscriptions;

    @Autowired
    private StripeEventRepository events;

    private Long organizationId;

    /**
     * Unique per test method, and it has to be.
     *
     * <p>{@code stripe_customer_id} and {@code stripe_subscription_id} are unique across
     * the whole table — one Stripe customer resolves to exactly one organization, which is
     * how a webhook finds who it is about. Reusing a fixed id across tests that each create
     * a fresh organization violates that, and the constraint was right to refuse it.
     */
    private String nonce;

    @BeforeEach
    void setUp() {
        Organization organization =
                organizations.saveAndFlush(new Organization("Northwind " + System.nanoTime()));
        organizationId = organization.getId();
        nonce = String.valueOf(System.nanoTime());
        subscriptions.saveAndFlush(
                Subscription.startTrial(organizationId, Instant.now()));
    }

    /** Exactly the construction Stripe uses: HMAC-SHA256 over "timestamp.body". */
    private String sign(String payload, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return "t=" + timestamp + ",v1=" + hex;
    }

    private String checkoutCompleted(String eventId, Long organization, Plan plan) {
        return """
                {"id":"%s","object":"event","type":"checkout.session.completed","api_version":"2024-06-20",
                 "data":{"object":{"id":"cs_%s","object":"checkout.session",
                 "customer":"cus_%s","subscription":"sub_%s",
                 "metadata":{"freezehub_organization_id":"%d","freezehub_plan":"%s"}}}}
                """.formatted(eventId, nonce, nonce, nonce, organization, plan.name());
    }

    private org.springframework.test.web.servlet.ResultActions deliver(String payload, String signature)
            throws Exception {
        var request = post("/api/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);
        if (signature != null) {
            request = request.header("Stripe-Signature", signature);
        }
        return mockMvc.perform(request);
    }

    @Test
    void aSignedCheckoutActivatesTheSubscription() throws Exception {
        String payload = checkoutCompleted("evt_1_" + System.nanoTime(), organizationId, Plan.GROWTH);

        deliver(payload, sign(payload, Instant.now().getEpochSecond()))
                .andExpect(status().isOk());

        Subscription subscription = subscriptions.findByOrganizationId(organizationId).orElseThrow();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getPlan()).isEqualTo(Plan.GROWTH);
        assertThat(subscription.getStripeCustomerId()).isEqualTo("cus_" + nonce);
        assertThat(subscription.getStripeSubscriptionId()).isEqualTo("sub_" + nonce);
    }

    @Test
    void anUnsignedDeliveryChangesNothing() throws Exception {
        String payload = checkoutCompleted("evt_2_" + System.nanoTime(), organizationId, Plan.SCALE);

        deliver(payload, null).andExpect(status().isUnauthorized());

        assertThat(subscriptions.findByOrganizationId(organizationId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.TRIALING);
    }

    @Test
    void aForgedSignatureChangesNothing() throws Exception {
        // Someone who knows the payload shape but not the signing secret. This is the
        // attack the endpoint exists to withstand: without the check, anyone who can reach
        // it could put any organization on any plan.
        String payload = checkoutCompleted("evt_3_" + System.nanoTime(), organizationId, Plan.SCALE);
        String forged = "t=" + Instant.now().getEpochSecond() + ",v1=" + "0".repeat(64);

        deliver(payload, forged).andExpect(status().isUnauthorized());

        assertThat(subscriptions.findByOrganizationId(organizationId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(subscriptions.findByOrganizationId(organizationId).orElseThrow().getPlan())
                .isEqualTo(Plan.TRIAL);
    }

    @Test
    void aCapturedDeliveryCannotBeReplayedLater() throws Exception {
        // Signed correctly, but with an old timestamp. Without the tolerance check a
        // delivery captured once could be replayed for ever — and a replayed
        // subscription.deleted suspends a paying customer.
        String payload = checkoutCompleted("evt_4_" + System.nanoTime(), organizationId, Plan.SCALE);
        long longAgo = Instant.now().minusSeconds(60 * 60 * 24).getEpochSecond();

        deliver(payload, sign(payload, longAgo)).andExpect(status().isUnauthorized());

        assertThat(subscriptions.findByOrganizationId(organizationId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.TRIALING);
    }

    @Test
    void aRedeliveredEventIsAppliedOnlyOnce() throws Exception {
        // Stripe redelivers on any non-2xx and sometimes on a 2xx it did not see.
        String eventId = "evt_5_" + System.nanoTime();
        String payload = checkoutCompleted(eventId, organizationId, Plan.GROWTH);
        String signature = sign(payload, Instant.now().getEpochSecond());

        deliver(payload, signature).andExpect(status().isOk());

        // Downgrade underneath, then redeliver the same event. If idempotency were absent
        // the redelivery would silently put them back on GROWTH.
        Subscription subscription = subscriptions.findByOrganizationId(organizationId).orElseThrow();
        subscription.activate(Plan.STARTER, null, null, null);
        subscriptions.saveAndFlush(subscription);

        deliver(payload, signature).andExpect(status().isOk());

        assertThat(subscriptions.findByOrganizationId(organizationId).orElseThrow().getPlan())
                .isEqualTo(Plan.STARTER);
        assertThat(events.findById(eventId)).isPresent();
    }

    @Test
    void anEventForAnUnknownOrganizationIsAcceptedAndIgnored() throws Exception {
        // 200, not an error: anything else makes Stripe retry a lookup that will never
        // succeed, for ever.
        String payload = checkoutCompleted("evt_6_" + System.nanoTime(), 999_999_999L, Plan.GROWTH);

        deliver(payload, sign(payload, Instant.now().getEpochSecond()))
                .andExpect(status().isOk());

        assertThat(subscriptions.findByOrganizationId(organizationId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.TRIALING);
    }

    @Test
    void anUnrecognisedEventTypeIsAcceptedAndIgnored() throws Exception {
        String payload = """
                {"id":"evt_7_%d","object":"event","type":"customer.discount.created",
                 "api_version":"2024-06-20","data":{"object":{"id":"di_1","object":"discount"}}}
                """.formatted(System.nanoTime());

        deliver(payload, sign(payload, Instant.now().getEpochSecond()))
                .andExpect(status().isOk());
    }
}
