package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.util.EventIds;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the palette-resolving SQLite predicate translator.
 * The crux: user-supplied strings/UUIDs are resolved to integer palette
 * ids up front, so the emitted SQL is integers + fixed identifiers only —
 * there is no string-escaping surface, and an unknown value compiles to a
 * constant-false clause.
 */
class SqlitePredicateToSqlTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // Fake palette: "break"->7, "Alice"->9; PLAYER->3. Everything else absent.
    private final SqlitePredicateToSql translator = new SqlitePredicateToSql(
            new SqlitePredicateToSql.Palette() {
                @Override
                public Integer dictId(String value) {
                    return switch (value) {
                        case "break" -> 7;
                        case "Alice" -> 9;
                        default -> null;
                    };
                }

                @Override
                public Integer uuidId(UUID value) {
                    return PLAYER.equals(value) ? 3 : null;
                }
            });

    private String translate(QueryPredicate predicate) {
        return translator.translate(List.of(predicate));
    }

    @Test
    void resolvesStringEqualityToPaletteId() {
        assertThat(translate(new QueryPredicate.Eq("event", "break"))).isEqualTo("event = 7");
    }

    @Test
    void resolvesUuidEqualityToPaletteId() {
        assertThat(translate(new QueryPredicate.Eq("source.playerId", PLAYER))).isEqualTo("player = 3");
    }

    @Test
    void unknownValueCompilesToConstantFalse() {
        assertThat(translate(new QueryPredicate.Eq("event", "never-seen"))).isEqualTo("0");
    }

    @Test
    void inDropsAbsentValuesAndKeepsPresentOnes() {
        assertThat(translate(new QueryPredicate.In("event", List.of("break", "ghost"))))
                .isEqualTo("event IN (7)");
        assertThat(translate(new QueryPredicate.In("event", List.of("ghost"))))
                .isEqualTo("0");
    }

    @Test
    void timeRangeInlinesEpochSeconds() {
        Instant lo = Instant.ofEpochSecond(1000);
        Instant hi = Instant.ofEpochSecond(2000);
        assertThat(translate(new QueryPredicate.Range("occurred", lo, hi)))
                .isEqualTo("(occurred >= 1000 AND occurred <= 2000)");
    }

    @Test
    void coordinateRangeAlsoBoundsChunkExpressionForTheIndex() {
        // x in [10, 40] => (x>>4) in [floorDiv(10,16), floorDiv(40,16)] = [0, 2].
        assertThat(translate(new QueryPredicate.Range("location.x", 10, 40)))
                .isEqualTo("(x >= 10 AND x <= 40 AND (x >> 4) >= 0 AND (x >> 4) <= 2)");
        // Negative coordinates floor toward -inf, matching x>>4 at write time.
        assertThat(translate(new QueryPredicate.Range("location.z", -20, -1)))
                .isEqualTo("(z >= -20 AND z <= -1 AND (z >> 4) >= -2 AND (z >> 4) <= -1)");
    }

    @Test
    void idEqualityMapsUuidThroughEventIdsSequence() {
        UUID id = EventIds.newId();
        assertThat(translate(new QueryPredicate.Eq("id", id)))
                .isEqualTo("seq = " + EventIds.sequenceOf(id));
    }

    @Test
    void regexOnInternedColumnIsUnsupported() {
        // server is an interned dict column; a regex can't run against the int.
        assertThatThrownBy(() -> translate(new QueryPredicate.Eq(
                "server", Pattern.compile("survi.*"))))
                .isInstanceOf(SqlitePredicateToSql.UnsupportedPredicateException.class);
    }

    @Test
    void blobOnlyFieldIsUnsupported() {
        assertThatThrownBy(() -> translate(new QueryPredicate.Eq("message", "hi")))
                .isInstanceOf(SqlitePredicateToSql.UnsupportedPredicateException.class);
    }

    @Test
    void hostileStringNeverReachesSqlOnlyItsResolvedId() {
        // A SQL-injection attempt as an interned value resolves to "no such id"
        // (the fake palette doesn't know it) and compiles to a constant — the
        // raw string is never inlined into the SQL text.
        String sql = translate(new QueryPredicate.Eq("server", "'; DROP TABLE records;--"));
        assertThat(sql).isEqualTo("0");
        assertThat(sql).doesNotContain("DROP");
    }

    @Test
    void andOrNotCompose() {
        QueryPredicate and = new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("event", "break"),
                new QueryPredicate.Eq("source.playerId", PLAYER)));
        assertThat(translate(and)).isEqualTo("(event = 7 AND player = 3)");

        QueryPredicate not = new QueryPredicate.Not(new QueryPredicate.Eq("event", "break"));
        assertThat(translate(not)).isEqualTo("(NOT event = 7)");

        // Existence on an interned column.
        assertThat(translate(new QueryPredicate.Exists("target", true)))
                .isEqualTo("(target IS NOT NULL)");
    }

    @Test
    void multipleTopLevelPredicatesJoinWithAnd() {
        String sql = translator.translate(List.of(
                new QueryPredicate.Eq("event", "break"),
                new QueryPredicate.Eq("origin.kind", "Alice")));
        assertThat(sql).isEqualTo("(event = 7 AND origin_kind = 9)");
        assertThat(translator.translate(List.of())).isEmpty();
    }
}
