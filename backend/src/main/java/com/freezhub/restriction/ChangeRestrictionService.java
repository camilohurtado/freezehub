package com.freezhub.restriction;

import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.catalog.TeamRepository;
import com.freezhub.notification.NotificationEvent;
import com.freezhub.notification.NotificationOutbox;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChangeRestrictionService {

    private final ChangeRestrictionRepository changeRestrictionRepository;
    private final TeamRepository teamRepository;
    private final ApplicationRepository applicationRepository;
    private final EnvironmentRepository environmentRepository;
    private final NotificationOutbox notificationOutbox;

    public ChangeRestrictionService(ChangeRestrictionRepository changeRestrictionRepository,
                                    TeamRepository teamRepository,
                                    ApplicationRepository applicationRepository,
                                    EnvironmentRepository environmentRepository,
                                    NotificationOutbox notificationOutbox) {
        this.changeRestrictionRepository = changeRestrictionRepository;
        this.teamRepository = teamRepository;
        this.applicationRepository = applicationRepository;
        this.environmentRepository = environmentRepository;
        this.notificationOutbox = notificationOutbox;
    }

    /**
     * Restrictions for one organization, optionally narrowed to the given statuses.
     * An empty/absent status filter means "no status filter", not "match nothing".
     */
    public List<ChangeRestriction> list(Long organizationId, Collection<RestrictionStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return changeRestrictionRepository.findAllByOrganizationIdOrderByStartsAtAscIdAsc(organizationId);
        }
        return changeRestrictionRepository
                .findAllByOrganizationIdAndStatusInOrderByStartsAtAscIdAsc(organizationId, statuses);
    }

    /**
     * One restriction with its scope, or 404 if it is unknown or belongs to another
     * organization - the two are indistinguishable on purpose, so cross-tenant existence
     * is never revealed.
     */
    @Transactional(readOnly = true)
    public ChangeRestriction get(Long organizationId, Long restrictionId) {
        return findOwnedWithScope(organizationId, restrictionId);
    }

    /**
     * Loads a restriction owned by the organization, with its scope initialised.
     *
     * <p>Every caller that returns the entity for mapping needs this: the scope
     * collections are LAZY (so that listing stays a single query) and
     * {@code spring.jpa.open-in-view} is disabled, so anything left uninitialised here
     * fails when the controller maps the response outside the transaction. Doing it in
     * one place means a new endpoint cannot forget it.
     *
     * <p>Must be called from within a transaction.
     */
    private ChangeRestriction findOwnedWithScope(Long organizationId, Long restrictionId) {
        ChangeRestriction restriction = changeRestrictionRepository
                .findByIdAndOrganizationId(restrictionId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restriction not found"));

        Hibernate.initialize(restriction.getTeamIds());
        Hibernate.initialize(restriction.getApplicationIds());
        Hibernate.initialize(restriction.getEnvironmentIds());

        return restriction;
    }

    @Transactional
    public ChangeRestriction create(Long organizationId, Long createdBy, RestrictionRequest request) {
        validateRequest(organizationId, request);

        ChangeRestriction created = changeRestrictionRepository.save(new ChangeRestriction(
                organizationId,
                request.name(),
                request.description(),
                request.reason(),
                request.level(),
                request.startsAt(),
                request.endsAt(),
                createdBy,
                request.scope().teamIds(),
                request.scope().applicationIds(),
                request.scope().environmentIds()));

        // Same transaction as the creation itself: the restriction and the intent to
        // announce it are committed together or not at all (FZ-040).
        notificationOutbox.enqueue(organizationId, created.getId(), NotificationEvent.SCHEDULED);

        return created;
    }

    /**
     * Full replacement of a restriction's editable state (FZ-023), permitted only while it
     * is still SCHEDULED - once it is active, completed or cancelled it is a record of what
     * happened and editing it would rewrite history. The same invariants as creation are
     * re-checked, so an update can never leave a restriction in a state creation would have
     * rejected.
     */
    @Transactional
    public ChangeRestriction update(Long organizationId, Long restrictionId, RestrictionRequest request) {
        ChangeRestriction restriction = findOwnedWithScope(organizationId, restrictionId);

        if (!restriction.isScheduled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a SCHEDULED restriction can be updated; this one is " + restriction.getStatus());
        }

        validateRequest(organizationId, request);

        restriction.replaceEditableState(
                request.name(),
                request.description(),
                request.reason(),
                request.level(),
                request.startsAt(),
                request.endsAt(),
                request.scope().teamIds(),
                request.scope().applicationIds(),
                request.scope().environmentIds());

        return restriction;
    }

    /**
     * Cancels a SCHEDULED or ACTIVE restriction (FZ-024). Cancelling is terminal and
     * preserves the record - the restriction stays visible with status CANCELLED rather
     * than being deleted, because what was communicated to engineers actually happened.
     *
     * <p>A COMPLETED restriction cannot be cancelled (it already ran its course) and
     * neither can an already-CANCELLED one; both are 409. Cancellation is deliberately
     * not idempotent: the backlog scopes this to "a scheduled or active restriction", and
     * a repeat cancel signals the caller believed it was still live.
     */
    @Transactional
    public ChangeRestriction cancel(Long organizationId, Long restrictionId) {
        ChangeRestriction restriction = findOwnedWithScope(organizationId, restrictionId);

        if (!restriction.isCancellable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a SCHEDULED or ACTIVE restriction can be cancelled; this one is "
                            + restriction.getStatus());
        }

        restriction.cancel();
        notificationOutbox.enqueue(organizationId, restriction.getId(), NotificationEvent.CANCELLED);

        return restriction;
    }

    /** Invariants shared by create and update, so the two cannot drift apart. */
    private void validateRequest(Long organizationId, RestrictionRequest request) {
        Set<Long> teamIds = request.scope().teamIds();
        Set<Long> applicationIds = request.scope().applicationIds();
        Set<Long> environmentIds = request.scope().environmentIds();

        validatePeriod(request.startsAt(), request.endsAt(), Instant.now());
        validateScopeNotEmpty(teamIds, applicationIds, environmentIds);

        requireAllOwned(organizationId, teamIds, teamRepository::countByOrganizationIdAndIdIn, "Team");
        requireAllOwned(organizationId, applicationIds, applicationRepository::countByOrganizationIdAndIdIn,
                "Application");
        requireAllOwned(organizationId, environmentIds, environmentRepository::countByOrganizationIdAndIdIn,
                "Environment");
    }

    /**
     * Domain invariants 1 and 2: startsAt must precede endsAt, and a newly scheduled
     * restriction cannot already be entirely in the past. A startsAt in the past is
     * permitted - only the whole window being past is rejected. FZ-025 will activate
     * such a restriction on its next pass.
     */
    static void validatePeriod(Instant startsAt, Instant endsAt, Instant now) {
        if (!startsAt.isBefore(endsAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startsAt must be earlier than endsAt");
        }
        if (!endsAt.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Restriction cannot be entirely in the past");
        }
    }

    /** Domain invariant 3: a restriction requires at least one scope target. */
    static void validateScopeNotEmpty(Set<Long> teamIds, Set<Long> applicationIds, Set<Long> environmentIds) {
        if (teamIds.isEmpty() && applicationIds.isEmpty() && environmentIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one scope target is required");
        }
    }

    /**
     * Tenant isolation: a restriction scope cannot reference a resource owned by another
     * organization. Unknown and cross-tenant ids are both reported as 404 so that the
     * existence of another organization's resources is never revealed.
     */
    private void requireAllOwned(Long organizationId, Set<Long> ids,
                                 BiFunction<Long, Collection<Long>, Long> countOwned, String resourceName) {
        if (ids.isEmpty()) {
            return;
        }
        if (countOwned.apply(organizationId, ids) != ids.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, resourceName + " not found");
        }
    }

}
