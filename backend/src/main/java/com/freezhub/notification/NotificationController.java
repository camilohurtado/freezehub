package com.freezhub.notification;

import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final NotificationRetryService retries;

    public NotificationController(NotificationHistoryService history,
                                  NotificationRetryService retries) {
        this.history = history;
        this.retries = retries;
    }

    @GetMapping
    public List<NotificationEventResponse> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                                @RequestParam(required = false) Integer limit) {
        return history.history(caller.organizationId(),
                limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT));
    }

    /**
     * Sends an announcement again (FZ-119).
     *
     * <p>Requeues only the deliveries that failed. Re-sending the whole event would
     * announce a freeze a second time to every channel that already accepted it, turning
     * a fix for the one person who was not told into a duplicate for everyone who was.
     *
     * <p>Answers with the count rather than 204, because "nothing was failing" and
     * "three were requeued" are different outcomes and the screen says which.
     */
    @PostMapping("/retry")
    public RetryResponse retry(@AuthenticationPrincipal AuthenticatedUser caller,
                               @Valid @RequestBody RetryRequest request) {
        return new RetryResponse(retries.retry(
                caller.organizationId(), request.restrictionId(), request.event(), Instant.now()));
    }

    /** How many deliveries were put back in the queue. */
    public record RetryResponse(int requeued) {
    }
}
