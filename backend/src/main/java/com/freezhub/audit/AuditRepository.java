package com.freezhub.audit;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * One organization's trail, newest first, from an optional cursor.
     *
     * <p>Keyset pagination on {@code id} rather than an offset, because the trail is
     * append-only: with an offset, every row written between two page requests shifts the
     * window and the reader silently skips entries. A cursor cannot.
     */
    @Query("""
            select e from AuditEvent e
             where e.organizationId = :organizationId
               and (:beforeId is null or e.id < :beforeId)
             order by e.id desc
            """)
    List<AuditEvent> findPage(@Param("organizationId") Long organizationId,
                              @Param("beforeId") Long beforeId,
                              Limit limit);

    /** Everything that happened to one resource, newest first. */
    @Query("""
            select e from AuditEvent e
             where e.organizationId = :organizationId
               and e.resourceType = :resourceType
               and e.resourceId = :resourceId
             order by e.id desc
            """)
    List<AuditEvent> findForResource(@Param("organizationId") Long organizationId,
                                     @Param("resourceType") AuditResourceType resourceType,
                                     @Param("resourceId") Long resourceId,
                                     Limit limit);

}
