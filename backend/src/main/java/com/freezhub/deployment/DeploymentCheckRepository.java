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
