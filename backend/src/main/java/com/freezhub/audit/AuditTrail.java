package com.freezhub.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what happened (FZ-060).
 *
 * <p>{@code Propagation.MANDATORY}, for the same reason as {@code NotificationOutbox}:
 * the event is written in the <strong>same transaction</strong> as the change it
 * describes, so the two cannot disagree. A change that rolls back leaves no audit entry
 * claiming it happened, and an entry never exists for a change that did not.
 *
 * <p>Calling this outside a transaction would silently reintroduce that gap, so it fails
 * loudly instead.
 */
@Service
public class AuditTrail {

    private final AuditRepository auditRepository;

    public AuditTrail(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long organizationId, AuditActor actor, AuditAction action,
                       AuditResourceType resourceType, Long resourceId) {
        record(organizationId, actor, action, resourceType, resourceId, null);
    }

    /**
     * @param details JSON, or null. For an update this is the changed fields with their
     *                before and after values — without which "someone edited this" is not
     *                an answer to anything (decision {@code D-1}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long organizationId, AuditActor actor, AuditAction action,
                       AuditResourceType resourceType, Long resourceId, String details) {
        auditRepository.save(
                new AuditEvent(organizationId, actor, action, resourceType, resourceId, details));
    }

}
