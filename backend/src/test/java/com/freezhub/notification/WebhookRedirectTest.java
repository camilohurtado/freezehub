package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import com.freezhub.shared.web.OutboundAddressPolicy;
import com.freezhub.shared.web.OutboundHttpConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * A destination that answers with a redirect (`FZ-126`, `OI-23`).
 *
 * <p>The attack this closes: an attacker-controlled endpoint passes every check the product
 * made — it is https, it resolves to a public address — and then answers `302` to
 * `http://169.254.169.254/`, where a container runtime serves the task's own credentials.
 * Validating the configured URL proves nothing about where the request finally goes.
 *
 * <p>Two servers stand in for the two hops, both on loopback because that is what a test
 * can have, so the address policy is stubbed to permit them. That is deliberate and it is
 * the only way to see this half: with the real policy the first hop is refused for being
 * loopback and the redirect never happens. The policy's own rules have their own test.
 *
 * <p>No Spring context: what is under test is the client assembly, and
 * {@link OutboundHttpConfig#deliveryClient} is that assembly — the same call the bean makes.
 */
class WebhookRedirectTest {

    /** Permits everything, so that only the redirect behaviour decides the outcome. */
    private static final OutboundAddressPolicy PERMISSIVE =
            new OutboundAddressPolicy(host -> new InetAddress[0]) {
                @Override
                public void requireCallable(java.net.URI uri) {
                }
            };

    private final RestClient guarded =
            OutboundHttpConfig.deliveryClient(PERMISSIVE, Duration.ofSeconds(5), Duration.ofSeconds(5));

    private HttpServer redirector;
    private HttpServer secondHop;
    private final AtomicInteger secondHopCalls = new AtomicInteger();

    @BeforeEach
    void twoHops() throws IOException {
        secondHop = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secondHop.createContext("/", exchange -> {
            secondHopCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        secondHop.start();

        redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirector.createContext("/", exchange -> {
            // Standing in for http://169.254.169.254/latest/meta-data/
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + secondHop.getAddress().getPort() + "/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirector.start();
    }

    @AfterEach
    void stop() {
        redirector.stop(0);
        secondHop.stop(0);
    }

    private String firstHop() {
        return "http://127.0.0.1:" + redirector.getAddress().getPort() + "/hook";
    }

    @Test
    void doesNotFollowARedirectToASecondAddress() {
        assertThatThrownBy(() -> guarded.post()
                .uri(firstHop())
                .body("{}")
                .retrieve()
                .toBodilessEntity())
                .hasMessageContaining("redirect");

        // The point of the whole story: the second address was never called.
        assertThat(secondHopCalls.get()).isZero();
    }

    @Test
    void theRefusalReachesTheCustomerAsADeliveryFailure() {
        // A silent drop would leave an administrator with a destination that never works
        // and nothing on the notifications screen saying why.
        Integration destination = new Integration(1L, IntegrationType.WEBHOOK,
                "{\"url\":\"" + firstHop() + "\"}");
        ChangeRestriction restriction = new ChangeRestriction(
                1L, "Black Friday Freeze", null, "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE, Instant.now(), Instant.now().plusSeconds(3600),
                1L, Set.of(), Set.of(), Set.of());
        Notification notification = new Notification(1L, 1L, 1L, NotificationEvent.SCHEDULED);

        assertThatThrownBy(() ->
                new WebhookNotificationSender(guarded).send(notification, restriction, destination))
                .hasMessageContaining("redirect");

        assertThat(secondHopCalls.get()).isZero();
    }

}
