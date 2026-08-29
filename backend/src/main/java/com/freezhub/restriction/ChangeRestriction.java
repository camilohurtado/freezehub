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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.hibernate.annotations.BatchSize;

/**
 * A time-bounded policy affecting software changes (01-domain.md).
 *
 * <p>Scope collections are LAZY (FZ-021: EAGER made every list call 3N+1 queries) and
 * batch-fetched, so policy evaluation initialises the scope of a whole candidate set in
 * three queries rather than three per restriction — it is asked once per deployment.
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

    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "change_restriction_team", joinColumns = @JoinColumn(name = "restriction_id"))
    @Column(name = "team_id")
    private Set<Long> teamIds = new LinkedHashSet<>();

    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "change_restriction_application", joinColumns = @JoinColumn(name = "restriction_id"))
    @Column(name = "application_id")
    private Set<Long> applicationIds = new LinkedHashSet<>();

    @BatchSize(size = 100)
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

    /**
     * Only a SCHEDULED or ACTIVE restriction can be cancelled (01-domain.md lifecycle).
     * A COMPLETED one has already run its course and a CANCELLED one is already there.
     */
    public boolean isCancellable() {
        return this.status == RestrictionStatus.SCHEDULED || this.status == RestrictionStatus.ACTIVE;
    }

    /**
     * Moves the restriction to CANCELLED (FZ-024). Domain invariants 5 and 6 follow from
     * this being terminal: a cancelled restriction can never become ACTIVE again, and it
     * no longer affects policy evaluation.
     */
    void cancel() {
        this.status = RestrictionStatus.CANCELLED;
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

    /**
     * Whether this restriction covers a deployment of {@code applicationId} to
     * {@code environmentId} (FZ-051).
     *
     * <p>The rule is 01-domain.md's, verbatim: OR within a dimension, AND across
     * dimensions, and an <strong>empty dimension is a wildcard</strong> rather than an
     * empty set — which is what lets "freeze every deployment to production" be expressed
     * by naming only an environment. Invariant 3 keeps that safe: a restriction with no
     * targets at all is rejected at creation, so this can never mean "everything".
     *
     * <p>Teams and applications are separate dimensions, so naming both narrows rather
     * than widens — "this application, and only while it belongs to this team". A scope
     * naming a team and an application outside it therefore matches nothing, which is
     * accepted rather than rejected at creation because membership is mutable.
     *
     * <p>Says nothing about <em>when</em>: whether the restriction is in force is decided
     * from the persisted timestamps by the caller's query.
     */
    public boolean covers(Long applicationId, Long environmentId, Set<Long> applicationTeamIds) {
        return (teamIds.isEmpty() || !Collections.disjoint(teamIds, applicationTeamIds))
                && (applicationIds.isEmpty() || applicationIds.contains(applicationId))
                && (environmentIds.isEmpty() || environmentIds.contains(environmentId));
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
