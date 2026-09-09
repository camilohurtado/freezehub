package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.freezhub.ContainersConfig;
import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * A receiver that accepts the connection and never answers (`FZ-065`).
 *
 * <p>The case that matters is not a webhook that fails — those are retried — but one that
 * hangs. Delivery runs inside a transaction on a single dispatcher shared by every
 * organization, so a socket that never returns holds a database connection open and stops
 * announcements for every other tenant behind it.
 *
 * <p>Written against the {@link WebhookNotificationSender} bean the application actually
 * builds, not a hand-made {@code RestClient}: what is under test is the configuration
 * Spring hands it, which is exactly where the timeout is or is not.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(ContainersConfig.class)
class WebhookTimeoutTest {

    /**
     * Comfortably above the configured read timeout and far below "for ever". Before
     * `FZ-065` the send sat in {@code SocketDispatcher.read0} until this assertion killed
     * the thread; there was no bound at all.
     */
    private static final Duration MUST_RETURN_WITHIN = Duration.ofSeconds(25);

    @Autowired
    private WebhookNotificationSender sender;

    @Test
    void aReceiverThatNeverAnswersDoesNotBlockDeliveryForever() throws Exception {
        try (ServerSocket tarpit = new ServerSocket(0)) {
            ExecutorService accepting = Executors.newSingleThreadExecutor();
            accepting.submit(() -> {
                // Accept and hold. Never write a byte back, never close: the shape of a
                // receiver whose own upstream is wedged.
                try (Socket held = tarpit.accept()) {
                    Thread.sleep(Duration.ofMinutes(5));
                } catch (IOException | InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });

            Integration destination = new Integration(1L, IntegrationType.WEBHOOK,
                    "{\"url\":\"http://127.0.0.1:" + tarpit.getLocalPort() + "/hook\"}");
            ChangeRestriction restriction = new ChangeRestriction(
                    1L, "Black Friday Freeze", null, "Revenue-critical period",
                    RestrictionLevel.HARD_FREEZE, Instant.now(), Instant.now().plusSeconds(3600),
                    1L, Set.of(), Set.of(), Set.of());
            Notification notification =
                    new Notification(1L, 1L, 1L, NotificationEvent.SCHEDULED);

            AtomicBoolean gaveUp = new AtomicBoolean();
            try {
                // A delivery failure is a fine outcome — being told nothing at all is not.
                assertTimeoutPreemptively(MUST_RETURN_WITHIN,
                        () -> {
                            try {
                                sender.send(notification, restriction, destination);
                            } catch (NotificationDeliveryException expected) {
                                // The receiver never answered; giving up is the answer.
                                gaveUp.set(true);
                            }
                        },
                        "the sender never returned: a hung receiver holds the dispatcher, "
                                + "its transaction and its database connection open");
            } finally {
                accepting.shutdownNow();
            }

            // Not just "returned" — returned as a failure the retry policy can act on. A
            // silent success here would mean an announcement recorded as delivered to a
            // receiver that never acknowledged it.
            assertThat(gaveUp).isTrue();
        }
    }
}
