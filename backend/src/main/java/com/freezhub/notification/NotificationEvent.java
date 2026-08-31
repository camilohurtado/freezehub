package com.freezhub.notification;

/**
 * Restriction lifecycle events worth communicating (00-product.md).
 *
 * <p>Named for what happened rather than reusing RestrictionStatus: ACTIVATED is an
 * event, ACTIVE is a state, and STARTING_SOON is not a status at all.
 */
public enum NotificationEvent {
    SCHEDULED,
    /**
     * A freeze is about to begin (FZ-047). Alone among these, it is triggered by the
     * passage of time rather than by a state transition, so a scheduled sweep writes it
     * rather than a domain change. How far ahead is the organization's own setting.
     */
    STARTING_SOON,
    ACTIVATED,
    COMPLETED,
    CANCELLED
}
