package com.freezhub.notification;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Oldest first, so a backlog drains in the order events happened. */
    List<Notification> findAllByStatusOrderByIdAsc(NotificationStatus status, Limit limit);

    List<Notification> findAllByRestrictionIdOrderByIdAsc(Long restrictionId);

    boolean existsByRestrictionIdAndIntegrationIdAndEvent(
            Long restrictionId, Long integrationId, NotificationEvent event);

}
