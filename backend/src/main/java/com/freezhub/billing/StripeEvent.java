package com.freezhub.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A Stripe delivery that has already been acted on (FZ-084).
 *
 * <p>The event id is the primary key, so a duplicate is refused by the database rather
 * than by a check that could race with a concurrent redelivery.
 */
@Entity
@Table(name = "stripe_event")
public class StripeEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(nullable = false)
    private String type;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected StripeEvent() {
    }

    public StripeEvent(String eventId, String type, Instant receivedAt) {
        this.eventId = eventId;
        this.type = type;
        // Set here rather than in @PrePersist: the id is assigned rather than generated,
        // so Spring Data treats save() as a merge and the callback is not a dependable
        // place to populate a NOT NULL column.
        this.receivedAt = receivedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

}
