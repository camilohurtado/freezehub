package com.freezhub.billing;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Where Stripe tells us what happened (FZ-084).
 *
 * <p>Its own security chain, no credential, no CORS: the signature is the authentication,
 * and a browser has no business here.
 *
 * <p>The body is taken as a {@code String} rather than a DTO because the signature covers
 * the exact bytes Stripe sent. Letting Spring parse and re-serialise would change them and
 * every delivery would fail verification.
 */
@RestController
@RequestMapping("/api/webhooks/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeSignature signature;
    private final StripeEventHandler handler;

    public StripeWebhookController(StripeSignature signature, StripeEventHandler handler) {
        this.signature = signature;
        this.handler = handler;
    }

    @PostMapping
    public void receive(@RequestBody String payload,
                        @RequestHeader(value = "Stripe-Signature", required = false) String signatureHeader) {
        Event event;
        try {
            event = signature.verify(payload, signatureHeader);
        } catch (SignatureVerificationException rejected) {
            // 401 and nothing else. The reason is logged, never returned: telling a caller
            // whether the signature was malformed, stale or simply wrong helps only
            // someone trying to construct a valid one.
            log.warn("Rejected a Stripe delivery: {}", rejected.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid signature");
        }

        handler.handle(event);
        // 200 whether or not this was a redelivery. Stripe retries anything else, and a
        // duplicate is not a failure — it is the thing idempotency exists to absorb.
    }
}
