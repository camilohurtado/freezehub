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

    /**
     * Every restriction in force at {@code now} for one organization (FZ-051).
     *
     * <p><strong>Derived from the persisted timestamps, never from {@code status}.</strong>
     * The status column is maintained by a reconciler running on an interval (FZ-025), so
     * it lags by up to that interval; reading {@code status = ACTIVE} here would allow a
     * deployment during a freeze whose activation tick had not yet run — a hole that would
     * surface only under load or just after a restart. {@code CANCELLED} is the one status
     * consulted, because cancellation is an intent that no timestamp expresses.
     *
     * <p>Half-open window: a restriction ending at 09:30 does not block a deployment at
     * 09:30, matching the lifecycle reconciler's own predicates.
     */
    @Query("""
            select r from ChangeRestriction r
             where r.organizationId = :organizationId
               and r.startsAt <= :now
               and r.endsAt > :now
               and r.status <> :cancelled
            """)
    List<ChangeRestriction> findInForce(@Param("organizationId") Long organizationId,
                                        @Param("now") Instant now,
                                        @Param("cancelled") RestrictionStatus cancelled);

    /**
     * Restrictions that have not started yet and could be near enough to warn about
     * (FZ-047), paired with their organization's lead time in minutes.
     *
     * <p>Bounded by {@code horizon}, the largest lead time any organization has set, so
     * the sweep considers a small window rather than every future restriction. Whether
     * each one is actually due is then decided per organization, because the lead time
     * differs between them.
     *
     * <p>Not tenant-scoped: this is a system sweep across the whole table, like the
     * lifecycle reconciler.
     */
    @Query("""
            select r, o.startingSoonLeadTimeMinutes
              from ChangeRestriction r
              join Organization o on o.id = r.organizationId
             where r.status <> :cancelled
               and r.startsAt > :now
               and r.startsAt <= :horizon
            """)
    List<Object[]> findApproaching(@Param("now") Instant now,
                                   @Param("horizon") Instant horizon,
                                   @Param("cancelled") RestrictionStatus cancelled);

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
