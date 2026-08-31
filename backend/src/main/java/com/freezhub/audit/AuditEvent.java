package com.freezhub.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An immutable record of something that happened (01-domain.md, FZ-060).
 *
 * <p><strong>Immutable by construction</strong>: there are no setters and nothing in the
 * application updates or deletes a row. That is enforcement against mistakes rather than
 * against a determined operator, who has database access anyway — the honest limit of an
 * audit trail stored beside the data it describes.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private AuditActor.AuditActorType actorType;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_label", nullable = false)
    private String actorLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    private AuditResourceType resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    AuditEvent(Long organizationId, AuditActor actor, AuditAction action,
               AuditResourceType resourceType, Long resourceId, String details) {
        this.organizationId = organizationId;
        this.actorType = actor.type();
        this.actorId = actor.id();
        this.actorLabel = actor.label();
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.details = details;
    }

    @PrePersist
    void onCreate() {
        this.occurredAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public AuditActor.AuditActorType getActorType() {
        return actorType;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditResourceType getResourceType() {
        return resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public String getDetails() {
        return details;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

}
