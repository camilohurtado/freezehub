package com.freezhub.restriction;

import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.catalog.TeamRepository;
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

    public ChangeRestrictionService(ChangeRestrictionRepository changeRestrictionRepository,
                                    TeamRepository teamRepository,
                                    ApplicationRepository applicationRepository,
                                    EnvironmentRepository environmentRepository) {
        this.changeRestrictionRepository = changeRestrictionRepository;
        this.teamRepository = teamRepository;
        this.applicationRepository = applicationRepository;
        this.environmentRepository = environmentRepository;
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
     *
     * <p>Transactional and initialising the scope collections explicitly: they are LAZY
     * (so that listing stays a single query) and {@code spring.jpa.open-in-view} is
     * disabled, so anything left uninitialised here would fail when the controller maps
     * the response outside this transaction.
     */
    @Transactional(readOnly = true)
    public ChangeRestriction get(Long organizationId, Long restrictionId) {
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

        return changeRestrictionRepository.save(new ChangeRestriction(
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
        ChangeRestriction restriction = changeRestrictionRepository
                .findByIdAndOrganizationId(restrictionId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restriction not found"));

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
