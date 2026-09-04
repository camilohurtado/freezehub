package com.freezhub.subscription;

/**
 * A write refused because the organization is suspended or cancelled (FZ-081).
 *
 * <p>Also {@code 402}: it is the plan — or the absence of one — refusing, not validation
 * and not permission.
 *
 * <p>What this deliberately does <em>not</em> reach is {@code /api/policy/evaluate}. A
 * suspended organization's freezes keep being enforced exactly as before ({@code D-21}):
 * failing that endpoint would take every one of the customer's pipelines down over an
 * invoice, and allowing through it would silently lift every freeze at the worst possible
 * moment. Suspension removes the ability to change things, not the answers already given.
 */
public class OrganizationSuspendedException extends RuntimeException {

    private final SubscriptionStatus status;

    public OrganizationSuspendedException(SubscriptionStatus status) {
        super("This organization's subscription is " + status
                + ". Existing restrictions are still enforced, but nothing can be changed.");
        this.status = status;
    }

    public SubscriptionStatus status() {
        return status;
    }
}
