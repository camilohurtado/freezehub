package com.freezhub.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The before-and-after diff an update event carries (FZ-060, decision D-1). */
class FieldChangesTest {

    @Test
    void recordsOnlyTheFieldsThatChanged() {
        // A diff listing every field, most unchanged, buries the one thing the reader
        // came for — and most edits touch one or two fields.
        String json = FieldChanges.builder()
                .compare("name", "Black Friday", "Black Friday Freeze")
                .compare("reason", "unchanged", "unchanged")
                .toJson();

        assertThat(json).contains("name").doesNotContain("reason");
    }

    @Test
    void carriesBothTheOldAndTheNewValue() {
        String json = FieldChanges.builder().compare("level", "ADVISORY", "HARD_FREEZE").toJson();

        assertThat(json).isEqualTo("{\"level\":{\"from\":\"ADVISORY\",\"to\":\"HARD_FREEZE\"}}");
    }

    @Test
    void isNullWhenNothingChanged() {
        // No event should carry an empty diff — the caller checks isEmpty and records
        // nothing at all.
        FieldChanges unchanged = FieldChanges.builder().compare("name", "same", "same");

        assertThat(unchanged.isEmpty()).isTrue();
        assertThat(unchanged.toJson()).isNull();
    }

    @Test
    void treatsNullsAsValuesRatherThanAsAbsent() {
        assertThat(FieldChanges.builder().compare("description", null, "added").toJson())
                .contains("\"from\":null");
        assertThat(FieldChanges.builder().compare("description", "removed", null).toJson())
                .contains("\"to\":null");
        assertThat(FieldChanges.builder().compare("description", null, null).isEmpty()).isTrue();
    }

    @Test
    void comparesCollectionsByContentRatherThanByOrder() {
        // Scope is a Set, and two equal sets must not read as a change just because
        // Hibernate returned them in a different order.
        assertThat(FieldChanges.builder()
                .compare("teamIds", Set.of(1L, 2L), Set.of(2L, 1L))
                .isEmpty()).isTrue();

        assertThat(FieldChanges.builder()
                .compare("teamIds", Set.of(1L), Set.of(1L, 2L))
                .toJson()).contains("teamIds");
    }

    @Test
    void rendersCollectionsInAStableOrder() {
        // Sorted, so the same change always produces the same JSON and two entries can be
        // compared by eye.
        String json = FieldChanges.builder()
                .compare("applicationIds", Set.of(), Set.of(3L, 1L, 2L))
                .toJson();

        assertThat(json).contains("[\"1\",\"2\",\"3\"]");
    }

    @Test
    void escapesValuesRatherThanConcatenatingThem() {
        // Names are user-supplied. A quote in one must not produce a row nothing can parse.
        String json = FieldChanges.builder()
                .compare("name", "plain", "a \"quoted\" name")
                .toJson();

        assertThat(json).contains("\\\"quoted\\\"");
    }

}
