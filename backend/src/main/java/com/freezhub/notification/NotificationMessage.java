package com.freezhub.notification;

import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

/**
 * The words an announcement uses.
 *
 * <p>Separate from any channel so every channel says the same thing, and so the wording
 * can be asserted without sending anything.
 *
 * <p>Times are rendered in **UTC and labelled as such**. A freeze announcement goes to a
 * distributed audience with no single local zone, and an unlabelled time would be read
 * differently by everyone who saw it (01-domain.md invariants 9 and 10).
 */
public final class NotificationMessage {

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneOffset.UTC);

    private NotificationMessage() {
    }

    /** A single line summarising what happened, suitable as a heading. */
    public static String headline(NotificationEvent event, ChangeRestriction restriction) {
        String blocking = restriction.getLevel() == RestrictionLevel.HARD_FREEZE
                ? "Deployments are blocked"
                : "Advisory";

        return switch (event) {
            case SCHEDULED -> "Deployment freeze scheduled: " + restriction.getName();
            case ACTIVATED -> "Deployment freeze is now active: " + restriction.getName()
                    + " — " + blocking;
            case COMPLETED -> "Deployment freeze finished: " + restriction.getName();
            case CANCELLED -> "Deployment freeze cancelled: " + restriction.getName();
        };
    }

    /** The full plain-text announcement. */
    public static String body(NotificationEvent event, ChangeRestriction restriction) {
        StringBuilder text = new StringBuilder(headline(event, restriction));

        text.append("\nReason: ").append(restriction.getReason());
        if (restriction.getDescription() != null && !restriction.getDescription().isBlank()) {
            text.append("\n").append(restriction.getDescription());
        }
        text.append("\nWindow: ")
                .append(UTC.format(restriction.getStartsAt()))
                .append(" to ")
                .append(UTC.format(restriction.getEndsAt()))
                .append(" UTC");

        if (event == NotificationEvent.CANCELLED) {
            text.append("\nThis restriction no longer applies.");
        }

        return text.toString();
    }

}
