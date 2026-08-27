package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The wording of an announcement, asserted without sending anything.
 *
 * <p>These run at UTC-5 like the rest of the suite is elsewhere; the point of the UTC
 * assertions below is that the announcement does not follow the server's zone.
 */
class NotificationMessageTest {

    private ChangeRestriction restriction(RestrictionLevel level, String description) {
        return new ChangeRestriction(
                1L, "Black Friday Freeze", description, "Revenue-critical period", level,
                Instant.parse("2026-11-27T14:00:00Z"), Instant.parse("2026-12-02T09:30:00Z"),
                1L, Set.of(), Set.of(), Set.of());
    }

    @Test
    void saysWhetherDeploymentsAreBlocked() {
        // The single most consequential thing a reader needs from a freeze announcement.
        String blocking = NotificationMessage.headline(
                NotificationEvent.ACTIVATED, restriction(RestrictionLevel.HARD_FREEZE, null));
        String advisory = NotificationMessage.headline(
                NotificationEvent.ACTIVATED, restriction(RestrictionLevel.ADVISORY, null));

        assertThat(blocking).contains("Deployments are blocked");
        assertThat(advisory).contains("Advisory").doesNotContain("Deployments are blocked");
    }

    @Test
    void statesTimesInUtcAndLabelsThem() {
        // A freeze announcement reaches a distributed audience with no shared local zone;
        // an unlabelled time would be read differently by everyone who saw it.
        String body = NotificationMessage.body(
                NotificationEvent.SCHEDULED, restriction(RestrictionLevel.HARD_FREEZE, null));

        assertThat(body).contains("27 Nov 2026 14:00");
        assertThat(body).contains("2 Dec 2026 09:30");
        assertThat(body).contains("UTC");
    }

    @Test
    void alwaysIncludesTheReason() {
        for (NotificationEvent event : NotificationEvent.values()) {
            assertThat(NotificationMessage.body(event, restriction(RestrictionLevel.HARD_FREEZE, null)))
                    .as("event %s", event)
                    .contains("Revenue-critical period");
        }
    }

    @Test
    void includesTheDescriptionOnlyWhenThereIsOne() {
        assertThat(NotificationMessage.body(
                NotificationEvent.SCHEDULED, restriction(RestrictionLevel.HARD_FREEZE, "Peak trading")))
                .contains("Peak trading");
        assertThat(NotificationMessage.body(
                NotificationEvent.SCHEDULED, restriction(RestrictionLevel.HARD_FREEZE, null)))
                .doesNotContain("null");
    }

    @Test
    void saysExplicitlyThatACancelledRestrictionNoLongerApplies() {
        // Otherwise a cancellation reads much like the original announcement.
        assertThat(NotificationMessage.body(
                NotificationEvent.CANCELLED, restriction(RestrictionLevel.HARD_FREEZE, null)))
                .contains("no longer applies");
    }

    @Test
    void distinguishesEveryEvent() {
        ChangeRestriction restriction = restriction(RestrictionLevel.HARD_FREEZE, null);

        assertThat(NotificationMessage.headline(NotificationEvent.SCHEDULED, restriction))
                .contains("scheduled");
        assertThat(NotificationMessage.headline(NotificationEvent.ACTIVATED, restriction))
                .contains("now active");
        assertThat(NotificationMessage.headline(NotificationEvent.COMPLETED, restriction))
                .contains("finished");
        assertThat(NotificationMessage.headline(NotificationEvent.CANCELLED, restriction))
                .contains("cancelled");
    }

}
