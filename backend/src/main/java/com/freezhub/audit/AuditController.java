package com.freezhub.audit;

import com.freezhub.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the audit trail (FZ-060).
 *
 * <p>Administrator-only. The trail names who did what, which is exactly the kind of thing
 * an organization would not want every member browsing.
 *
 * <p>Read-only by design: there is no endpoint to write, edit or delete an entry. Events
 * are written by the actions that cause them, in the same transaction.
 */
@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class AuditController {

    /** Enough to be useful in one call; capped so a client cannot ask for the whole table. */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final AuditRepository auditRepository;

    public AuditController(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * The organization's trail, newest first.
     *
     * <p>{@code beforeId} is a cursor, not an offset: the trail is append-only, so with an
     * offset every entry written between two requests shifts the window and the reader
     * silently skips some. Pass the {@code id} of the last entry you saw.
     */
    @GetMapping
    public List<AuditEventResponse> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                         @RequestParam(required = false) Long beforeId,
                                         @RequestParam(required = false) Integer limit) {
        return auditRepository
                .findPage(caller.organizationId(), beforeId, Limit.of(capped(limit)))
                .stream()
                .map(AuditEventResponse::from)
                .toList();
    }

    /** Everything that happened to one resource — the "what happened to this freeze?" view. */
    @GetMapping("/resource")
    public List<AuditEventResponse> forResource(@AuthenticationPrincipal AuthenticatedUser caller,
                                                @RequestParam AuditResourceType resourceType,
                                                @RequestParam Long resourceId,
                                                @RequestParam(required = false) Integer limit) {
        return auditRepository
                .findForResource(caller.organizationId(), resourceType, resourceId, Limit.of(capped(limit)))
                .stream()
                .map(AuditEventResponse::from)
                .toList();
    }

    private int capped(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(limit, 1, MAX_LIMIT);
    }

}
