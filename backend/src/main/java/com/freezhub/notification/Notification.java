package com.freezhub.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One outbox row: one delivery attempt, to one destination, for one lifecycle event
 * (FZ-040).
 *
 * <p>Per-destination grain matters — Slack succeeding while a webhook fails is a normal
 * outcome, and a single status per event could not express it.
 *
 * <p>Rows are written in the same transaction as the domain change they describe, which
 * is what makes the intent survive a crash between "restriction activated" and
 * "notification queued" (02-architecture.md). Delivery itself is FZ-041 onwards; nothing
 * here sends anything.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "restriction_id", nullable = false)
    private Long restrictionId;

    @Column(name = "integration_id", nullable = false)
    private Long integrationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationEvent event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
    }

    public Notification(Long organizationId, Long restrictionId, Long integrationId,
                        NotificationEvent event) {
        this.organizationId = organizationId;
        this.restrictionId = restrictionId;
        this.integrationId = integrationId;
        this.event = event;
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
    }

    /** Recorded as delivered. Called by the channel adapters (FZ-041 onwards). */
    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
        this.attempts += 1;
    }

    /**
     * Records a failed attempt. Stays PENDING so it remains eligible for retry; FZ-044
     * decides when a row is abandoned as FAILED.
     */
    public void markAttemptFailed(String error) {
        this.attempts += 1;
        this.lastError = error;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getRestrictionId() {
        return restrictionId;
    }

    public Long getIntegrationId() {
        return integrationId;
    }

    public NotificationEvent getEvent() {
        return event;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

}
