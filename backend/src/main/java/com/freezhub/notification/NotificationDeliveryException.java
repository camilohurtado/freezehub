package com.freezhub.notification;

/**
 * A delivery attempt failed.
 *
 * <p>Its message is stored in {@code notification.last_error} and read by whoever is
 * diagnosing a silent freeze announcement, so it must say what went wrong **without
 * quoting a destination credential** — a Slack webhook URL is a bearer token, and the
 * default message of most HTTP client exceptions contains the request URI.
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }

}
