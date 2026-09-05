package com.freezhub.demo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Demo requests (FZ-083).
 *
 * <p>No method here takes an organization id, and none should: this table sits outside the
 * tenant boundary because a demo request belongs to nobody yet. If a query with an
 * organization filter ever appears here, something has gone wrong upstream.
 */
public interface DemoRequestRepository extends JpaRepository<DemoRequest, Long> {

    /**
     * Requests still waiting to be announced, oldest first.
     *
     * <p>Bounded by attempts as well as by time, so a permanently broken webhook stops
     * being retried instead of being hit on every pass for ever ({@code RetryPolicy}).
     */
    @Query("""
            select d from DemoRequest d
            where d.notifiedAt is null
              and d.nextNotifyAt <= :now
              and d.notifyAttempts < :maxAttempts
            order by d.id asc
            """)
    List<DemoRequest> findPendingNotification(@Param("now") Instant now,
                                              @Param("maxAttempts") int maxAttempts,
                                              Limit limit);
}
