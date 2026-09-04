package com.freezhub.subscription;

/**
 * Where an organization stands commercially (FZ-081).
 *
 * <p>None of these change a policy answer. A suspended organization's freezes are still
 * enforced, byte for byte, because making {@code /api/policy/evaluate} fail would take
 * every one of that customer's pipelines down over an invoice, and making it allow would
 * silently lift every freeze at the exact moment of a commercial dispute (decision
 * {@code D-21}).
 */
public enum SubscriptionStatus {

    /** In a 14-day trial. Everything is available; nothing has been paid. */
    TRIALING,

    ACTIVE,

    /**
     * A payment failed. Deliberately still writable: dunning is a conversation, and
     * locking an organization out on the first failed charge punishes an expired card.
     */
    PAST_DUE,

    /**
     * Trial ended without a subscription, or the subscription lapsed. The human API is
     * read-only and notifications stop; the Policy API is untouched.
     */
    SUSPENDED,

    /** Deliberately ended. Same reach as {@link #SUSPENDED}. */
    CANCELLED;

    /**
     * Whether this organization may still change things.
     *
     * <p>The leverage suspension provides is real without being dangerous: a suspended
     * customer cannot schedule the next freeze, change a scope, or receive a notification.
     * That is enough to force the conversation. Breaking their deployments is not.
     */
    public boolean allowsWrites() {
        return this == TRIALING || this == ACTIVE || this == PAST_DUE;
    }

    /** Whether announcements go out. Suspended organizations stop being announced for. */
    public boolean allowsNotifications() {
        return allowsWrites();
    }
}
