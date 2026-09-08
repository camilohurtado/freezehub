package com.freezhub.notification;

import com.freezhub.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What FreezeHub announced, and whether each channel accepted it (`1g`, FZ-115).
 *
 * <p><strong>Administrators only</strong>, like integrations and the audit trail. A
 * delivery record names the destination it was sent to — which channel, and the error it
 * returned — and that is the configuration only an administrator can see in the first
 * place. "Was Slack told?" is a fair question for anyone to ask, but the answer here
 * carries more than the answer.
 */
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class NotificationController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final NotificationHistoryService history;

    public NotificationController(NotificationHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public List<NotificationEventResponse> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                                @RequestParam(required = false) Integer limit) {
        return history.history(caller.organizationId(),
                limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT));
    }
}
