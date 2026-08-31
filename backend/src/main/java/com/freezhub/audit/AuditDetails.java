package com.freezhub.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code details} JSON for an event that is not a before-and-after diff.
 *
 * <p>Exists so nothing builds that JSON by concatenating strings. Most of what goes in
 * here is user-supplied — an API key's name, an organization's name — and a value
 * containing a quote would otherwise produce a corrupt row that no reader can parse.
 */
public final class AuditDetails {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> values = new LinkedHashMap<>();

    public static AuditDetails builder() {
        return new AuditDetails();
    }

    private AuditDetails() {
    }

    public AuditDetails with(String field, Object value) {
        values.put(field, value == null ? null : String.valueOf(value));
        return this;
    }

    public String toJson() {
        if (values.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not serialise audit details", impossible);
        }
    }

}
