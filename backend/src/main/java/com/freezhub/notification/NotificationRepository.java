package com.freezhub.notification;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Notifications due for an attempt now.
     *
     * <p>The {@code nextAttemptAt} filter is what bounds retry (FZ-044): without it every
     * PENDING row was eligible on every pass, so a failing destination was hammered
     * continuously (`OI-1`).
     *
     * <p>Oldest first, so a backlog drains in the order events happened.
     */
    List<Notification> findAllByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            NotificationStatus status, Instant dueBy, Limit limit);

    List<Notification> findAllByRestrictionIdOrderByIdAsc(Long restrictionId);

    /** One organization's announcements, newest first — the history screen's only read. */
    List<Notification> findAllByOrganizationIdOrderByIdDesc(Long organizationId);

    /** How many announcements one restriction produced (FZ-112). */
    long countByOrganizationIdAndRestrictionId(Long organizationId, Long restrictionId);

    /** And how many of them never arrived. */
    long countByOrganizationIdAndRestrictionIdAndStatus(
            Long organizationId, Long restrictionId, NotificationStatus status);

    boolean existsByRestrictionIdAndIntegrationIdAndEvent(
            Long restrictionId, Long integrationId, NotificationEvent event);

}
