package com.freezhub.restriction;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChangeRestrictionRepository extends JpaRepository<ChangeRestriction, Long> {

    /**
     * Promotes restrictions whose start time has arrived (FZ-025).
     *
     * <p>Set-based and driven entirely by persisted timestamps, so it is idempotent and
     * safe to re-run: a restart simply reconciles again from the database rather than
     * relying on anything held in memory.
     *
     * <p>Restricted to SCHEDULED, which is what makes cancellation prevent future
     * activation - a CANCELLED restriction can never match. {@code updatedAt} is set
     * explicitly because a bulk JPQL update bypasses {@code @PreUpdate}.
     */
    /**
     * The rows {@link #activateDue} is about to change. Read before the bulk update
     * because a set-based UPDATE returns a count, not the rows — and FZ-040 has to queue a
     * notification per restriction that actually transitioned.
     *
     * <p>Same predicate as the update, so the two cannot disagree.
     */
    @Query("""
            select r from ChangeRestriction r
             where r.status = :scheduled
               and r.startsAt <= :now
               and r.endsAt > :now
            """)
    List<ChangeRestriction> findDueForActivation(@Param("now") Instant now,
                                                 @Param("scheduled") RestrictionStatus scheduled);

    /** The rows {@link #completeDue} is about to change; same predicate as that update. */
    @Query("""
            select r from ChangeRestriction r
             where r.status in :openStatuses
               and r.endsAt <= :now
            """)
    List<ChangeRestriction> findDueForCompletion(@Param("now") Instant now,
                                                 @Param("openStatuses") Collection<RestrictionStatus> openStatuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChangeRestriction r
               set r.status = :active, r.updatedAt = :now
             where r.status = :scheduled
               and r.startsAt <= :now
               and r.endsAt > :now
            """)
    int activateDue(@Param("now") Instant now,
                    @Param("scheduled") RestrictionStatus scheduled,
                    @Param("active") RestrictionStatus active);

    /**
     * Completes restrictions whose end time has passed (FZ-025).
     *
     * <p>Covers SCHEDULED as well as ACTIVE on purpose: if the application was down for
     * the whole of a restriction's window, the restriction still elapsed, and leaving it
     * SCHEDULED for ever would be wrong. CANCELLED is excluded - cancellation is terminal.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChangeRestriction r
               set r.status = :completed, r.updatedAt = :now
             where r.status in :openStatuses
               and r.endsAt <= :now
            """)
    int completeDue(@Param("now") Instant now,
                    @Param("openStatuses") Collection<RestrictionStatus> openStatuses,
                    @Param("completed") RestrictionStatus completed);

    /** Tenant-scoped lookup: a restriction owned by another organization is simply absent. */
    Optional<ChangeRestriction> findByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * Ordered soonest-first, tie-broken by id so the ordering is total and the result is
     * deterministic across calls. Scope collections are LAZY and deliberately not touched
     * here: the list is a summary, so listing costs one query regardless of row count.
     */
    List<ChangeRestriction> findAllByOrganizationIdOrderByStartsAtAscIdAsc(Long organizationId);

    List<ChangeRestriction> findAllByOrganizationIdAndStatusInOrderByStartsAtAscIdAsc(
            Long organizationId, Collection<RestrictionStatus> statuses);

}
