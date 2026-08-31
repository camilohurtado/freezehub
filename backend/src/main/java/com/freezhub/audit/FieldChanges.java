package com.freezhub.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the before-and-after JSON an update event carries (decision {@code D-1}).
 *
 * <p>Only fields that actually changed are included. A diff listing every field, most of
 * them unchanged, buries the one thing the reader is looking for — and on a restriction,
 * where most edits touch one or two fields, that is nearly all of it.
 *
 * <p>Shape:
 * <pre>{"level": {"from": "ADVISORY", "to": "HARD_FREEZE"}}</pre>
 */
public final class FieldChanges {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Map<String, Object>> changes = new LinkedHashMap<>();

    public static FieldChanges builder() {
        return new FieldChanges();
    }

    private FieldChanges() {
    }

    /** Records the field only if the two values differ. Nulls compare equal to nulls. */
    public FieldChanges compare(String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", stringify(before));
            change.put("to", stringify(after));
            changes.put(field, change);
        }
        return this;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** The JSON, or null when nothing changed — so no event carries an empty diff. */
    public String toJson() {
        if (changes.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(changes);
        } catch (JsonProcessingException impossible) {
            // Only Strings, numbers and collections of them ever reach here.
            throw new IllegalStateException("Could not serialise audit details", impossible);
        }
    }

    /**
     * Everything is rendered as a String or a collection of them.
     *
     * <p>The trail is read by people, not replayed by machines, and a stable textual form
     * means a later change to how a field is typed cannot make old entries unreadable.
     */
    private Object stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Iterable<?> values) {
            return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                    .map(String::valueOf)
                    .sorted()
                    .toList();
        }
        return String.valueOf(value);
    }

}
