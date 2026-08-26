package com.freezhub.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates and summarises each channel's {@code config} (FZ-045).
 *
 * <p>{@code integration.config} is opaque to the rest of the system on purpose
 * (03-data-model.md) — each channel needs different settings. That opacity only works if
 * *something* owns the meaning, which is this: the one place that knows what a Slack
 * config or a webhook config has to contain.
 *
 * <p>It also decides what may be shown back. A Slack webhook URL is a bearer credential:
 * anyone holding it can post into that channel. It is stored, never returned.
 */
public final class IntegrationConfigs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IntegrationConfigs() {
    }

    /** Rejects a config the owning channel could not act on. */
    public static void validate(IntegrationType type, String config) {
        JsonNode parsed = parse(config);

        switch (type) {
            case SLACK -> requireHttpsUrl(parsed, "webhookUrl", "a Slack incoming webhook URL");
            case WEBHOOK -> requireHttpsUrl(parsed, "url", "an HTTPS endpoint URL");
            case EMAIL -> requireRecipients(parsed);
        }
    }

    /**
     * What may safely be shown back to a client: enough to tell destinations apart, never
     * enough to reuse one.
     */
    public static String summarise(IntegrationType type, String config) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(config);
        } catch (JsonProcessingException unreadable) {
            return "unreadable configuration";
        }

        return switch (type) {
            // Host only. The path segment of a Slack webhook URL *is* the secret.
            case SLACK -> hostOf(parsed.path("webhookUrl").asText(""), "Slack");
            case WEBHOOK -> hostOf(parsed.path("url").asText(""), "webhook");
            case EMAIL -> {
                int count = parsed.path("recipients").size();
                yield count == 1 ? "1 recipient" : count + " recipients";
            }
        };
    }

    private static String hostOf(String url, String fallback) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? fallback : host;
        } catch (IllegalArgumentException notAUrl) {
            return fallback;
        }
    }

    private static JsonNode parse(String config) {
        try {
            JsonNode parsed = MAPPER.readTree(config);
            if (parsed == null || !parsed.isObject()) {
                throw badRequest("Configuration must be a JSON object");
            }
            return parsed;
        } catch (JsonProcessingException malformed) {
            throw badRequest("Configuration is not valid JSON");
        }
    }

    private static void requireHttpsUrl(JsonNode config, String field, String description) {
        String value = config.path(field).asText("");
        if (value.isBlank()) {
            throw badRequest("Configuration requires \"" + field + "\": " + description);
        }
        // https only: these carry credentials and freeze announcements.
        if (!value.startsWith("https://")) {
            throw badRequest("\"" + field + "\" must be an https URL");
        }
        try {
            URI.create(value);
        } catch (IllegalArgumentException notAUrl) {
            throw badRequest("\"" + field + "\" is not a valid URL");
        }
    }

    private static void requireRecipients(JsonNode config) {
        JsonNode recipients = config.path("recipients");
        if (!recipients.isArray() || recipients.isEmpty()) {
            throw badRequest("Configuration requires \"recipients\": a non-empty list of email addresses");
        }
        for (JsonNode recipient : recipients) {
            String address = recipient.asText("");
            if (address.isBlank() || !address.contains("@")) {
                throw badRequest("\"" + address + "\" is not a valid email address");
            }
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

}
