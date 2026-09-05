package com.freezhub.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.freezhub.ContainersConfig;
import com.freezhub.notification.RetryPolicy;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Announcing a demo request, and what happens when that fails (FZ-083).
 *
 * <p>The webhook points at a closed port, so every send genuinely fails — no mock, and the
 * retry bookkeeping is exercised for real.
 *
 * <p>The notifier is <strong>autowired rather than constructed</strong>. Building it with
 * {@code new} bypasses the Spring proxy, so {@code @Transactional} does not apply, the
 * entities come back detached and nothing is ever written — which is exactly what an
 * earlier version of this test proved, by failing.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
@TestPropertySource(properties = {
        "freezehub.demo-requests.slack-webhook=http://127.0.0.1:1/nowhere",
        "freezehub.demo-requests.enabled=false"
})
class DemoRequestNotificationTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    @Autowired
    private DemoRequestNotifier notifier;

    @Autowired
    private DemoRequestRepository requests;

    @BeforeEach
    void setUp() {
        requests.deleteAll();
        requests.save(new DemoRequest("Dana Ruiz", "dana@northwind.test", "Northwind",
                null, null, null, NOW));
    }

    @Test
    void aFailedAnnouncementIsRetriedRatherThanLost() {
        assertThat(notifier.notifyPending(NOW)).isZero();

        DemoRequest request = requests.findAll().getFirst();
        assertThat(request.getNotifyAttempts()).isEqualTo(1);
        assertThat(request.hasBeenNotified()).isFalse();
        assertThat(request.getNotifyError()).isNotNull();
        // Backed off, so the next pass does not hammer a destination that just failed.
        assertThat(request.getNextNotifyAt()).isAfter(NOW);
    }

    @Test
    void theWebhookNeverReachesTheStoredError() {
        // That URL is a bearer credential, and notify_error is read by whoever is
        // debugging. Spring puts the request URI in its exception messages, so only the
        // exception's class name is kept.
        notifier.notifyPending(NOW);

        assertThat(requests.findAll().getFirst().getNotifyError())
                .doesNotContain("127.0.0.1")
                .doesNotContain("nowhere");
    }

    @Test
    void nothingIsRetriedBeforeItsBackoffHasElapsed() {
        notifier.notifyPending(NOW);
        int afterFirst = requests.findAll().getFirst().getNotifyAttempts();

        // Immediately again: due time has not arrived, so this must be a no-op.
        notifier.notifyPending(NOW);

        assertThat(requests.findAll().getFirst().getNotifyAttempts()).isEqualTo(afterFirst);
    }

    @Test
    void aPermanentlyBrokenWebhookIsEventuallyGivenUpOn() {
        Instant clock = NOW;
        for (int attempt = 0; attempt < RetryPolicy.MAX_ATTEMPTS + 3; attempt++) {
            clock = clock.plusSeconds(3600);
            notifier.notifyPending(clock);
        }

        // Bounded, so a broken webhook is not hit on every pass for ever. The lead is
        // still in the table to be found, which is what makes giving up acceptable.
        DemoRequest request = requests.findAll().getFirst();
        assertThat(request.getNotifyAttempts()).isEqualTo(RetryPolicy.MAX_ATTEMPTS);
        assertThat(request.hasBeenNotified()).isFalse();
    }
}
