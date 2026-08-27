package com.freezhub.notification;

import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;

/**
 * Delivers one notification to one channel.
 *
 * <p>A port, so the dispatcher stays channel-agnostic and email (FZ-042) and webhook
 * (FZ-043) slot in without touching it. The same shape as {@code IdentityProvider}.
 *
 * <p>Implementations should throw on failure rather than returning a flag — the
 * dispatcher records the message, and a silent failure would mark a notification
 * delivered when nobody received it.
 */
public interface NotificationSender {

    /** The channel this sender handles. */
    IntegrationType type();

    void send(Notification notification, ChangeRestriction restriction, Integration destination);

}
