package com.freezhub.notification;

/**
 * Restriction lifecycle events worth communicating (00-product.md).
 *
 * <p>Named for what happened rather than reusing RestrictionStatus: ACTIVATED is an
 * event, ACTIVE is a state, and the product also lists a "starting soon" notification
 * that is not a status at all.
 */
public enum NotificationEvent {
    SCHEDULED,
    ACTIVATED,
    COMPLETED,
    CANCELLED
}
