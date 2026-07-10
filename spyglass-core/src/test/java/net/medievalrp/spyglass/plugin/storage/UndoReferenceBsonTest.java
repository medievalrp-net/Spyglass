package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import org.junit.jupiter.api.Test;

class UndoReferenceBsonTest {

    @Test
    void roundTripsEveryPredicateShapeAndValueType() {
        UUID player = UUID.randomUUID();
        Instant from = Instant.parse("2026-06-09T22:00:00.123Z");
        Instant to = Instant.parse("2026-06-10T01:30:00.456Z");
        QueryRequest request = new QueryRequest(
                List.of(
                        new QueryPredicate.Eq("source.playerId", player),
                        new QueryPredicate.Eq("event", "break"),
                        new QueryPredicate.Eq("location.x", 14000),
                        new QueryPredicate.Eq("amount", 42L),
                        new QueryPredicate.Eq("ratio", 0.5d),
                        new QueryPredicate.Eq("flag", true),
                        new QueryPredicate.Eq("maybe", null),
                        new QueryPredicate.In("event", List.of("break", "place")),
                        new QueryPredicate.Range("occurred", from, to),
                        new QueryPredicate.Range("location.y", 0, 256),
                        new QueryPredicate.Exists("target", true),
                        new QueryPredicate.Not(new QueryPredicate.Eq("event", "say")),
                        new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("a", "1"),
                                new QueryPredicate.Or(List.of(
                                        new QueryPredicate.Eq("b", "2"),
                                        new QueryPredicate.Exists("c", false))))),
                        new QueryPredicate.Or(List.of(
                                new QueryPredicate.Eq("d", "3")))),
                Sort.NEWEST_FIRST,
                1_000_000,
                EnumSet.of(Flag.NO_GROUP),
                false);
        Instant ceiling = Instant.parse("2026-06-10T02:00:00.789Z");

        String blob = UndoReferenceBson.encodeBase64(request, "ROLLBACK", ceiling);
        UndoReferenceBson.Reference decoded = UndoReferenceBson.decodeBase64(blob);

        assertThat(decoded.mode()).isEqualTo("ROLLBACK");
        assertThat(decoded.ceiling()).isEqualTo(ceiling);
        // Field-for-field equality: records give us deep equals across
        // the whole predicate tree, including the value types.
        assertThat(decoded.request()).isEqualTo(request);
    }

    // Pattern lacks equals(), so the deep-equality assert above cannot
    // cover it; compare pattern text + flags explicitly. Every substring
    // param (trg:, iname:, m:, ...) produces exactly this value shape,
    // and it used to kill rollback at the resume persist (#301).
    @Test
    void roundTripsPatternValues() {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote("Storm Caller"),
                java.util.regex.Pattern.CASE_INSENSITIVE);
        QueryRequest request = new QueryRequest(
                List.of(
                        new QueryPredicate.Eq("target", pattern),
                        new QueryPredicate.Not(new QueryPredicate.Eq("item.name", pattern))),
                Sort.NEWEST_FIRST, 100, EnumSet.of(Flag.NO_GROUP), false);

        String blob = UndoReferenceBson.encodeBase64(request, "ROLLBACK", Instant.EPOCH);
        UndoReferenceBson.Reference decoded = UndoReferenceBson.decodeBase64(blob);

        QueryPredicate.Eq eq = (QueryPredicate.Eq) decoded.request().predicates().get(0);
        java.util.regex.Pattern roundTripped = (java.util.regex.Pattern) eq.value();
        assertThat(roundTripped.pattern()).isEqualTo(pattern.pattern());
        assertThat(roundTripped.flags()).isEqualTo(pattern.flags());
        QueryPredicate.Not not = (QueryPredicate.Not) decoded.request().predicates().get(1);
        java.util.regex.Pattern nested =
                (java.util.regex.Pattern) ((QueryPredicate.Eq) not.predicate()).value();
        assertThat(nested.pattern()).isEqualTo(pattern.pattern());
        assertThat(nested.flags()).isEqualTo(pattern.flags());
    }

    @Test
    void rejectsUnknownVersions() {
        QueryRequest request = new QueryRequest(
                List.of(), Sort.OLDEST_FIRST, 10, EnumSet.noneOf(Flag.class), true);
        String blob = UndoReferenceBson.encodeBase64(request, "RESTORE", Instant.EPOCH);
        // Corrupt the version by decoding, bumping, re-encoding at the
        // byte level is overkill — assert the guard via a doctored blob.
        byte[] bytes = java.util.Base64.getDecoder().decode(blob);
        // BSON int32 "v" value sits right after the field name; flip it
        // by scanning for the name and bumping the following int.
        for (int i = 0; i < bytes.length - 6; i++) {
            if (bytes[i] == 'v' && bytes[i + 1] == 0) {
                bytes[i + 2] = 99;
                break;
            }
        }
        String doctored = java.util.Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> UndoReferenceBson.decodeBase64(doctored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version");
    }
}
