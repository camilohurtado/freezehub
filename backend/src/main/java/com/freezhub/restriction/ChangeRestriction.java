package com.freezhub.restriction;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A time-bounded policy affecting software changes (01-domain.md).
 *
 * <p>Scope is modelled as three {@link ElementCollection}s rather than separate entities:
 * scope rows have no identity of their own and are owned parts of this aggregate, so they
 * are persisted and removed with it. (Contrast {@code TeamApplication}, which joins two
 * independent aggregates and therefore is an entity.) Matching semantics for the three
 * dimensions are specified in 01-domain.md; evaluation is FZ-051.
 */
@Entity
@Table(name = "change_restriction")
public class ChangeRestriction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RestrictionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RestrictionLevel level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RestrictionStatus status;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "change_restriction_team", joinColumns = @JoinColumn(name = "restriction_id"))
    @Column(name = "team_id")
    private Set<Long> teamIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "change_restriction_application", joinColumns = @JoinColumn(name = "restriction_id"))
    @Column(name = "application_id")
    private Set<Long> applicationIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "change_restriction_environment", joinColumns = @JoinColumn(name = "restriction_id"))
    @Column(name = "environment_id")
    private Set<Long> environmentIds = new LinkedHashSet<>();

    protected ChangeRestriction() {
    }

    public ChangeRestriction(Long organizationId, String name, String description, String reason,
                             RestrictionLevel level, Instant startsAt, Instant endsAt, Long createdBy,
                             Set<Long> teamIds, Set<Long> applicationIds, Set<Long> environmentIds) {
        this.organizationId = organizationId;
        this.name = name;
        this.description = description;
        this.reason = reason;
        this.type = RestrictionType.DEPLOYMENT_FREEZE;
        this.level = level;
        this.status = RestrictionStatus.SCHEDULED;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdBy = createdBy;
        this.teamIds = new LinkedHashSet<>(teamIds);
        this.applicationIds = new LinkedHashSet<>(applicationIds);
        this.environmentIds = new LinkedHashSet<>(environmentIds);
    }

    /**
     * Replaces the editable state of a still-SCHEDULED restriction (FZ-023). Identity,
     * ownership, {@code type} and {@code status} are not editable here - status moves only
     * through cancellation (FZ-024) and the lifecycle (FZ-025).
     *
     * <p>The scope collections are cleared and refilled rather than reassigned: they are
     * Hibernate-managed once the entity is persistent, and swapping the instance out from
     * under the persistence context loses that tracking.
     */
    void replaceEditableState(String name, String description, String reason, RestrictionLevel level,
                              Instant startsAt, Instant endsAt,
                              Set<Long> teamIds, Set<Long> applicationIds, Set<Long> environmentIds) {
        this.name = name;
        this.description = description;
        this.reason = reason;
        this.level = level;
        this.startsAt = startsAt;
        this.endsAt = endsAt;

        this.teamIds.clear();
        this.teamIds.addAll(teamIds);
        this.applicationIds.clear();
        this.applicationIds.addAll(applicationIds);
        this.environmentIds.clear();
        this.environmentIds.addAll(environmentIds);
    }

    public boolean isScheduled() {
        return this.status == RestrictionStatus.SCHEDULED;
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

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getReason() {
        return reason;
    }

    public RestrictionType getType() {
        return type;
    }

    public RestrictionLevel getLevel() {
        return level;
    }

    public RestrictionStatus getStatus() {
        return status;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Set<Long> getTeamIds() {
        return teamIds;
    }

    public Set<Long> getApplicationIds() {
        return applicationIds;
    }

    public Set<Long> getEnvironmentIds() {
        return environmentIds;
    }

}
