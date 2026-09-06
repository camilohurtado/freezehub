package com.freezhub.billing;

import com.freezhub.subscription.Plan;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe credentials and the price each plan maps to (FZ-084).
 *
 * <p><strong>Where the secrets come from, and why not {@code D-3}.</strong> The backlog
 * asked for these to be held "the way destination credentials are". They cannot be, and
 * the difference is not cosmetic: {@code D-3} encrypts values that live in database rows
 * and belong to tenants, using a key supplied to the application. These are FreezeHub's
 * own credentials and they are what the application is configured with — encrypting them
 * would need a key, which would have to be configured, which is the problem again.
 *
 * <p>They come from the environment, populated from Secrets Manager at deploy time
 * ({@code FZ-063} provisions it). Nothing is committed, nothing is defaulted, and nothing
 * is logged. Absent configuration disables billing rather than starting with a blank key
 * that would fail on the first customer instead of on startup.
 */
@ConfigurationProperties(prefix = "freezehub.stripe")
public class StripeProperties {

    private String secretKey;

    /** The endpoint signing secret. Without it no webhook can be trusted, so none is accepted. */
    private String webhookSecret;

    /** Where Stripe sends the customer back to. */
    private String successUrl = "";

    private String cancelUrl = "";

    private String portalReturnUrl = "";

    /**
     * Stripe price identifiers, keyed {@code PLAN_PERIOD} — for example
     * {@code GROWTH_ANNUAL}.
     *
     * <p>A map rather than a field each: prices change, plans get added, and neither
     * should need a code change. Enterprise has no entry on purpose — it is priced per
     * deal and provisioned by an operator ({@code FZ-086}), never bought self-serve.
     */
    private Map<String, String> prices = new HashMap<>();

    public boolean isConfigured() {
        return notBlank(secretKey) && notBlank(webhookSecret);
    }

    /** Whether an inbound webhook can be verified at all. */
    public boolean canVerifyWebhooks() {
        return notBlank(webhookSecret);
    }

    public Optional<String> priceFor(Plan plan, BillingPeriod period) {
        return Optional.ofNullable(prices.get(plan.name() + "_" + period.name()))
                .filter(StripeProperties::notBlank);
    }

    /**
     * Which plan a Stripe price belongs to, for reading an event back.
     *
     * <p>Empty rather than a guess when the price is unknown or the key is malformed. The
     * caller treats that as "do not change the plan", which is the safe direction: a
     * misconfigured price should leave a customer on what they had, not move them to
     * something nobody sold them.
     */
    public Optional<Plan> planForPrice(String priceId) {
        if (!notBlank(priceId)) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> entry : prices.entrySet()) {
            if (!priceId.equals(entry.getValue())) {
                continue;
            }
            int separator = entry.getKey().lastIndexOf('_');
            if (separator <= 0) {
                return Optional.empty();
            }
            try {
                return Optional.of(Plan.valueOf(entry.getKey().substring(0, separator)));
            } catch (IllegalArgumentException unknownPlan) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getSuccessUrl() {
        return successUrl;
    }

    public void setSuccessUrl(String successUrl) {
        this.successUrl = successUrl;
    }

    public String getCancelUrl() {
        return cancelUrl;
    }

    public void setCancelUrl(String cancelUrl) {
        this.cancelUrl = cancelUrl;
    }

    public String getPortalReturnUrl() {
        return portalReturnUrl;
    }

    public void setPortalReturnUrl(String portalReturnUrl) {
        this.portalReturnUrl = portalReturnUrl;
    }

    public Map<String, String> getPrices() {
        return prices;
    }

    public void setPrices(Map<String, String> prices) {
        this.prices = prices;
    }
}
