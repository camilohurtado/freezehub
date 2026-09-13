package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.shared.web.OutboundAddressPolicy;
import com.freezhub.shared.web.OutboundHttpConfig;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A receiver that accepts the connection and never answers (`FZ-065`).
 *
 * <p>The case that matters is not a webhook that fails — those are retried — but one that
 * hangs. Delivery runs inside a transaction on a single dispatcher shared by every
 * organization, so a socket that never returns holds a database connection open and stops
 * announcements for every other tenant behind it.
 *
 * <p>Written against {@link OutboundHttpConfig#deliveryClient}, the same assembly the bean
 * is built from, rather than a hand-made {@code RestClient}: what is under test is the
 * configuration the sender is handed, which is exactly where the timeout is or is not.
 */
class WebhookTimeoutTest {

    /**
     * Comfortably above the configured read timeout and far below "for ever". Before
     * `FZ-065` the send sat in {@code SocketDispatcher.read0} until this assertion killed
     * the thread; there was no bound at all.
     */
    private static final Duration MUST_RETURN_WITHIN = Duration.ofSeconds(25);

    /**
     * Permits the loopback tarpit, because `FZ-126` now refuses it before a socket is ever
     * opened — correctly, and with its own tests. What this test is about is what happens
     * once a connection *is* made and nothing comes back, so the address rules are switched
     * off here rather than worked around.
     *
     * <p>No Spring context any more either: the sender is built from the same assembly the
     * bean uses, which is what carries the timeouts.
     */
    private static final OutboundAddressPolicy PERMISSIVE =
            new OutboundAddressPolicy(host -> new InetAddress[0]) {
                @Override
                public void requireCallable(java.net.URI uri) {
                }
            };

    private final WebhookNotificationSender sender = new WebhookNotificationSender(
            OutboundHttpConfig.deliveryClient(
                    PERMISSIVE, Duration.ofSeconds(5), Duration.ofSeconds(10)));

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
