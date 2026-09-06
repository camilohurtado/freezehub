package com.freezhub.billing;

import com.freezhub.shared.security.AuthenticatedUser;
import com.freezhub.subscription.Plan;
import com.freezhub.subscription.Subscription;
import com.freezhub.subscription.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sends an administrator to Stripe and brings them back (FZ-084).
 *
 * <p>Administrator-only: buying and cancelling are organization-level acts, like issuing a
 * machine credential.
 *
 * <p><strong>Reachable while suspended.</strong> {@code /api/billing/**} is excluded from
 * the write guard ({@code FZ-081}) because this is how an organization stops being
 * suspended — a read-only mode that locks out the only route to fixing it is a trap.
 *
 * <p>Nothing here grants anything. The returned URL sends the customer to Stripe; what
 * comes back is a redirect, and a redirect is a browser navigation anyone can type.
 * Entitlement changes only when Stripe tells us so over a signed webhook.
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    private final StripeCheckout checkout;
    private final SubscriptionService subscriptions;

    public BillingController(StripeCheckout checkout, SubscriptionService subscriptions) {
        this.checkout = checkout;
        this.subscriptions = subscriptions;
    }

    @PostMapping("/checkout-session")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public SessionResponse createCheckoutSession(@AuthenticationPrincipal AuthenticatedUser caller,
                                                 @Valid @RequestBody CheckoutRequest request) {
        if (request.plan() == Plan.ENTERPRISE || request.plan() == Plan.TRIAL) {
            // Enterprise is a conversation and a contract; a trial is not something to buy.
            // Refused here rather than left to fail on a missing price id, which would
            // read as a configuration fault rather than a deliberate answer.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    request.plan() + " cannot be bought self-serve");
        }

        Subscription subscription = subscriptions.of(caller.organizationId());
        String url = checkout.createSession(subscription, request.plan(), request.period(), caller.email());
        log.info("Checkout session created for organization {} on {}",
                caller.organizationId(), request.plan());
        return new SessionResponse(url);
    }

    /**
     * Stripe's own billing portal: card changes, invoices, cancellation.
     *
     * <p>Hosted by Stripe rather than rebuilt here, so no card detail ever reaches
     * FreezeHub and FreezeHub is never in PCI scope.
     */
    @PostMapping("/portal-session")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public SessionResponse createPortalSession(@AuthenticationPrincipal AuthenticatedUser caller) {
        Subscription subscription = subscriptions.of(caller.organizationId());
        if (subscription.getStripeCustomerId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This organization has never been billed, so there is no portal to open");
        }
        return new SessionResponse(checkout.createPortalSession(subscription));
    }

    public record CheckoutRequest(@NotNull Plan plan, @NotNull BillingPeriod period) {
    }

    public record SessionResponse(String url) {
    }
}
