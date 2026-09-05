package com.freezhub.demo;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link DemoRequestNotifier} (FZ-083).
 *
 * <p>Thin, like the other schedulers here, so the logic can be tested against an explicit
 * clock. Disabled with {@code freezehub.demo-requests.enabled=false}, which the tests use.
 *
 * <p>Every 30 seconds: a sales lead going unnoticed for half a minute is fine, and going
 * unnoticed for ten is not.
 */
@Component
@ConditionalOnProperty(name = "freezehub.demo-requests.enabled", havingValue = "true", matchIfMissing = true)
public class DemoRequestNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(DemoRequestNotificationScheduler.class);

    private final DemoRequestNotifier notifier;

    public DemoRequestNotificationScheduler(DemoRequestNotifier notifier) {
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = "${freezehub.demo-requests.interval:PT30S}")
    public void sweep() {
        int sent = notifier.notifyPending(Instant.now());
        if (sent > 0) {
            log.info("Announced {} demo request(s)", sent);
        }
    }
}
