package com.freezhub.billing;

import com.freezhub.subscription.Plan;
import com.freezhub.subscription.Subscription;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.param.billingportal.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;
import com.stripe.param.checkout.SessionCreateParams.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates the hosted pages a customer is sent to (FZ-084).
 *
 * <p>The organization id and the plan travel as Checkout <strong>metadata</strong>, so the
 * webhook that comes back says unambiguously what was bought and for whom. Inferring the
 * plan from a price id afterwards works until somebody renames a price, and then it moves
 * a customer onto something nobody sold them.
 */
@Service
public class StripeCheckout {

    private static final Logger log = LoggerFactory.getLogger(StripeCheckout.class);

    private final StripeProperties properties;

    public StripeCheckout(StripeProperties properties) {
        this.properties = properties;
    }

    public String createSession(Subscription subscription, Plan plan, BillingPeriod period,
                                String customerEmail) {
        requireConfigured();
        String priceId = properties.priceFor(plan, period).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No price is configured for " + plan + " " + period));

        var params = com.stripe.param.checkout.SessionCreateParams.builder()
                .setMode(Mode.SUBSCRIPTION)
                .setSuccessUrl(properties.getSuccessUrl())
                .setCancelUrl(properties.getCancelUrl())
                .setClientReferenceId(String.valueOf(subscription.getOrganizationId()))
                .addLineItem(LineItem.builder().setPrice(priceId).setQuantity(1L).build())
                .putMetadata(StripeEventHandler.ORGANIZATION_METADATA_KEY,
                        String.valueOf(subscription.getOrganizationId()))
                .putMetadata(StripeEventHandler.PLAN_METADATA_KEY, plan.name());

        if (subscription.getStripeCustomerId() != null) {
            params.setCustomer(subscription.getStripeCustomerId());
        } else if (customerEmail != null) {
            params.setCustomerEmail(customerEmail);
        }

        try {
            return client().checkout().sessions().create(params.build()).getUrl();
        } catch (StripeException failed) {
            throw unavailable(failed);
        }
    }

    public String createPortalSession(Subscription subscription) {
        requireConfigured();
        try {
            return client().billingPortal().sessions().create(SessionCreateParams.builder()
                    .setCustomer(subscription.getStripeCustomerId())
                    .setReturnUrl(properties.getPortalReturnUrl())
                    .build()).getUrl();
        } catch (StripeException failed) {
            throw unavailable(failed);
        }
    }

    private StripeClient client() {
        return StripeClient.builder().setApiKey(properties.getSecretKey()).build();
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Billing is not configured");
        }
    }

    /**
     * Never the provider's message.
     *
     * <p>Stripe's exceptions carry request ids and occasionally parameter values, and this
     * text reaches a browser. The class name is enough for a caller; the detail is logged.
     */
    private ResponseStatusException unavailable(StripeException failed) {
        log.error("Stripe rejected a request", failed);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not reach the payment provider");
    }
}
