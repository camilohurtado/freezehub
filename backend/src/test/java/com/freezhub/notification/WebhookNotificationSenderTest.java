package com.freezhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freezhub.integration.Integration;
import com.freezhub.integration.IntegrationType;
import com.freezhub.restriction.ChangeRestriction;
import com.freezhub.restriction.RestrictionLevel;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The webhook request and its payload (FZ-043).
 *
 * <p>Asserted closely because this body is a **contract** someone else's software parses —
 * a field quietly renamed here breaks a consumer with no compiler to catch it.
 */
class WebhookNotificationSenderTest {

    private static final String URL = "https://acme.test/hooks/freezehub?token=SECRETTOKENVALUE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer endpoint;
    private WebhookNotificationSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        endpoint = MockRestServiceServer.bindTo(builder).build();
        sender = new WebhookNotificationSender(builder);
    }

    private Integration destination(String config) {
        return new Integration(1L, IntegrationType.WEBHOOK, config);
    }

    private Integration destination() {
        return destination("{\"url\":\"" + URL + "\"}");
    }

    private ChangeRestriction restriction() {
        return new ChangeRestriction(
                1L, "Black Friday Freeze", "Peak trading", "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE,
                Instant.parse("2026-11-27T14:00:00Z"), Instant.parse("2026-12-02T09:30:00Z"),
                1L, Set.of(), Set.of(), Set.of());
    }

    private Notification notification(NotificationEvent event) {
        return new Notification(1L, 1L, 1L, event);
    }

    /** Captures the posted body so its structure can be asserted field by field. */
    private JsonNode postAndCapture(NotificationEvent event) throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        endpoint.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set(
                        ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                .getBodyAsString()))
                .andRespond(withSuccess());

        sender.send(notification(event), restriction(), destination());
        endpoint.verify();
        return MAPPER.readTree(captured.get());
    }

    @Test
    void postsAVersionedMachineReadableEvent() throws Exception {
        JsonNode payload = postAndCapture(NotificationEvent.ACTIVATED);

        // The version is what makes a later change to this contract safe to roll out.
        assertThat(payload.path("version").asInt()).isEqualTo(WebhookPayload.VERSION);
        assertThat(payload.path("event").asText()).isEqualTo("ACTIVATED");
        assertThat(payload.path("occurredAt").asText()).isNotBlank();
    }

    @Test
    void describesTheRestrictionInParseableFields() throws Exception {
        JsonNode restriction = postAndCapture(NotificationEvent.ACTIVATED).path("restriction");

        assertThat(restriction.path("name").asText()).isEqualTo("Black Friday Freeze");
        assertThat(restriction.path("reason").asText()).isEqualTo("Revenue-critical period");
        assertThat(restriction.path("description").asText()).isEqualTo("Peak trading");
        assertThat(restriction.path("type").asText()).isEqualTo("DEPLOYMENT_FREEZE");
        assertThat(restriction.path("level").asText()).isEqualTo("HARD_FREEZE");
        assertThat(restriction.path("status").asText()).isEqualTo("SCHEDULED");
    }

    @Test
    void sendsInstantsAsIso8601NotEpochNumbers() throws Exception {
        // A consumer parsing this needs a timestamp, not a number whose unit it must guess.
        JsonNode restriction = postAndCapture(NotificationEvent.ACTIVATED).path("restriction");

        assertThat(restriction.path("startsAt").asText()).isEqualTo("2026-11-27T14:00:00Z");
        assertThat(restriction.path("endsAt").asText()).isEqualTo("2026-12-02T09:30:00Z");
    }

    @Test
    void omitsScopeSoConsumersDoNotReimplementMatching() throws Exception {
        // Deliberate: deciding whether a deployment is affected is the Policy API's job
        // (FZ-051). A second implementation of those rules, outside FreezeHub, would drift.
        JsonNode payload = postAndCapture(NotificationEvent.ACTIVATED);

        assertThat(payload.has("scope")).isFalse();
        assertThat(payload.path("restriction").has("scope")).isFalse();
    }

    @Test
    void setsAnEventHeaderSoReceiversCanRouteWithoutParsing() {
        endpoint.expect(requestTo(URL))
                .andExpect(header("X-FreezeHub-Event", "CANCELLED"))
                .andExpect(content().contentType("application/json"))
                .andRespond(withSuccess());

        sender.send(notification(NotificationEvent.CANCELLED), restriction(), destination());

        endpoint.verify();
    }

    @Test
    void failsWhenTheEndpointRejectsTheRequest() {
        endpoint.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> sender.send(
                notification(NotificationEvent.ACTIVATED), restriction(), destination()))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void neverPutsTheEndpointUrlInTheFailureMessage() {
        // Webhook URLs commonly carry a token in the path or query; last_error is read by
        // support and must not become a place credentials are found.
        endpoint.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> sender.send(
                notification(NotificationEvent.ACTIVATED), restriction(), destination()))
                .hasMessageNotContaining("SECRETTOKENVALUE")
                .hasMessageNotContaining("acme.test");
    }

    @Test
    void failsClearlyWhenTheConfigHasNoUrl() {
        assertThatThrownBy(() -> sender.send(
                notification(NotificationEvent.ACTIVATED), restriction(), destination("{}")))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("no url");
    }

    @Test
    void handlesTheChannelItDeclares() {
        assertThat(sender.type()).isEqualTo(IntegrationType.WEBHOOK);
    }

}
