package com.freezhub.deployment;

import com.freezhub.policy.PolicyDecision;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeploymentCheckRepository extends JpaRepository<DeploymentCheck, Long> {

    /**
     * One organization's checks, newest first, filtered and cursor-paginated.
     *
     * <p>Keyset on {@code id} rather than an offset for the same reason as the audit
     * trail: this table only grows, and with an offset every row written between two page
     * requests shifts the window and the reader silently skips some. On a table written
     * once per deployment that is not a corner case, it is the normal case.
     *
     * <p>Null filters mean "no constraint", so one query serves the console's every view.
     */
    @Query("""
            select c from DeploymentCheck c
             where c.organizationId = :organizationId
               and (:beforeId is null or c.id < :beforeId)
               and (:application is null or c.application = :application)
               and (:environment is null or c.environment = :environment)
               and (:decision is null or c.decision = :decision)
             order by c.id desc
            """)
    List<DeploymentCheck> findPage(@Param("organizationId") Long organizationId,
                                   @Param("beforeId") Long beforeId,
                                   @Param("application") String application,
                                   @Param("environment") String environment,
                                   @Param("decision") PolicyDecision decision,
                                   Limit limit);

    /**
     * How many checks each day of the window allowed and refused (FZ-105).
     *
     * <p>Bucketed by <strong>UTC date</strong>, deliberately and explicitly. The screens
     * say "all times GMT", and a per-viewer local day would mean two people in different
     * offices disagreeing about how many deployments were refused yesterday — for a figure
     * that ends up in an incident review, one answer matters more than a convenient one.
     *
     * <p>Native, and {@code at time zone 'UTC'} spelled out, because that is the whole
     * point of the query. JPQL's {@code cast(... as LocalDate)} on a {@code timestamptz}
     * truncates in whatever zone the JDBC session happens to carry, which is the server's
     * — so the same data bucketed differently depending on where the process ran. It was
     * written that way first and a test in Asia/Tokyo caught it.
     *
     * <p>The decision is pivoted here rather than grouped and re-assembled in Java: two
     * columns of one row per day is what the caller wants, and it keeps the enum out of a
     * native projection.
     */
    @Query(nativeQuery = true, value = """
            select (c.checked_at at time zone 'UTC')::date as day,
                   count(*) filter (where c.decision = 'ALLOW') as allowed,
                   count(*) filter (where c.decision = 'BLOCK') as refused
              from deployment_check c
             where c.organization_id = :organizationId
               and c.checked_at >= :from
             group by 1
            """)
    List<DailyCount> countByDay(@Param("organizationId") Long organizationId,
                                @Param("from") Instant from);

    /**
     * How many of the organization's applications have ever asked (FZ-105).
     *
     * <p>Rendered as "11 of 14": the gap is the useful part, because it is the list of
     * services whose pipelines will sail straight through the next freeze.
     *
     * <p><strong>Catalogued applications that have been seen</strong>, not distinct names
     * appearing in checks. A check records the application name <em>as supplied</em>
     * ({@code FZ-070}) — an unrecognised name is the interesting case and is kept — so
     * counting distinct names includes typos and deleted entries. Against real data that
     * produced "5 of 4", which is not a coverage figure, it is a bug on a dashboard.
     */
    @Query(nativeQuery = true, value = """
            select count(*)
              from application a
             where a.organization_id = :organizationId
               and exists (select 1
                             from deployment_check c
                            where c.organization_id = a.organization_id
                              and c.application = a.name)
            """)
    long countApplicationsSeen(@Param("organizationId") Long organizationId);

    /**
     * How many deployments each restriction actually refused (FZ-105).
     *
     * <p>Native, because the matched restrictions are stored denormalised as JSON
     * ({@code FZ-070}) so the record stays true to what was matched at check time even
     * after a rename. Postgres can read that; JPQL cannot.
     *
     * <p><strong>Only HARD_FREEZE is credited.</strong> A blocked check lists every
     * restriction that matched, advisories included, but an advisory does not refuse
     * anything — it rode along. Counting it would tell an operator an advisory had
     * stopped deployments, which is the opposite of what advisory means.
     */
    @Query(nativeQuery = true, value = """
            select (r.value ->> 'id')::bigint as restrictionId, count(*) as refused
              from deployment_check c,
                   lateral jsonb_array_elements(c.matched_restrictions::jsonb) as r(value)
             where c.organization_id = :organizationId
               and c.decision = 'BLOCK'
               and c.matched_restrictions is not null
               and r.value ->> 'level' = 'HARD_FREEZE'
             group by 1
            """)
    List<RestrictionRefusalCount> countRefusalsByRestriction(@Param("organizationId") Long organizationId);

    /** Projection: one UTC day, and what it allowed and refused. */
    interface DailyCount {
        java.time.LocalDate getDay();

        long getAllowed();

        long getRefused();
    }

    /** Projection: one restriction and the deployments it refused. */
    interface RestrictionRefusalCount {
        Long getRestrictionId();

        long getRefused();
    }

    /**
     * Deletes one organization's expired checks (FZ-070).
     *
     * <p>Per organization rather than a single sweep over the table, because the retention
     * window is per organization — one customer keeping five years must not be truncated
     * to another's ninety days.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from DeploymentCheck c
             where c.organizationId = :organizationId
               and c.checkedAt < :before
            """)
    int deleteExpired(@Param("organizationId") Long organizationId, @Param("before") Instant before);

}
