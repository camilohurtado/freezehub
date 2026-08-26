package com.freezhub.notification;

/** Delivery state of one outbox row. Retry policy is FZ-044. */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
