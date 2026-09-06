package com.freezhub.billing;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.springframework.stereotype.Component;

/**
 * Turns a raw delivery into an event, or refuses it (FZ-084).
 *
 * <p>This is the whole authentication of the webhook endpoint. There is no credential, no
 * session and no allowlist of source addresses — the signature is what says a delivery
 * came from Stripe, and anything that fails it is indistinguishable from a forgery.
 *
 * <p>Delegates to {@code Webhook.constructEvent}, which is HMAC-SHA256 over
 * {@code "<timestamp>.<body>"} — the same construction FreezeHub uses for its own outbound
 * webhooks ({@code D-2}), pointed inward — <em>plus</em> a timestamp tolerance. That
 * tolerance is the part worth not writing by hand: without it a delivery captured once can
 * be replayed for ever, and a replayed {@code subscription.deleted} suspends a paying
 * customer.
 *
 * <p>The raw body must be exactly what Stripe sent, byte for byte. Re-serialising a parsed
 * object changes the bytes and the signature stops matching, which is why the controller
 * takes a String and not a DTO.
 */
@Component
public class StripeSignature {

    private final StripeProperties properties;

    public StripeSignature(StripeProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws SignatureVerificationException if the signature is absent, malformed, wrong,
     *                                        or outside the replay tolerance
     */
    public Event verify(String payload, String signatureHeader) throws SignatureVerificationException {
        if (!properties.canVerifyWebhooks()) {
            // No secret means nothing can be verified, so nothing is accepted. Failing
            // closed here is the only safe direction: the alternative is an endpoint that
            // changes entitlement for anyone who can reach it.
            throw new SignatureVerificationException(
                    "No webhook signing secret is configured", signatureHeader);
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            // Checked here because the SDK throws NullPointerException on a null header,
            // which would surface as a 500 — telling a caller the server broke when in
            // fact their delivery was unsigned.
            throw new SignatureVerificationException(
                    "No Stripe-Signature header was sent", signatureHeader);
        }
        return Webhook.constructEvent(payload, signatureHeader, properties.getWebhookSecret());
    }
}
