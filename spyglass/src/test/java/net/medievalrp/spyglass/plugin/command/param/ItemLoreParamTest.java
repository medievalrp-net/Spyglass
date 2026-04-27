package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

/**
 * {@link ItemLoreParam} unit tests. Structurally mirrors
 * {@link ItemNameParamTest} — same 5-path OR expansion — but the
 * sub-field is {@code lore} instead of {@code name}. Mongo regex on an
 * array field matches if ANY element matches, so we don't have to do
 * any per-element expansion; but that depends on the lore path being
 * {@code lore} exactly. Pin the shape so a field rename breaks loudly.
 */
class ItemLoreParamTest {

    private static final List<String> EXPECTED_PATHS = List.of(
            "item.lore",
            "beforeItem.lore",
            "afterItem.lore",
            "originalBlock.containerItems.lore",
            "newBlock.containerItems.lore");

    @Test
    void producesOrAcrossAllFiveLorePaths() throws Exception {
        QueryPredicate predicate = new ItemLoreParam().parse("ilore", "primordial", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        QueryPredicate.Or or = (QueryPredicate.Or) predicate;
        assertThat(or.predicates()).hasSize(5);
        List<String> fields = or.predicates().stream()
                .map(p -> ((QueryPredicate.Eq) p).field())
                .toList();
        assertThat(fields).containsExactlyElementsOf(EXPECTED_PATHS);
    }

    @Test
    void patternMatchesArbitraryLoreLines() throws Exception {
        // Lore is typically multiple lines stored as an array. Our pattern
        // is substring + case-insensitive, so any single line with the
        // token matches.
        QueryPredicate predicate = new ItemLoreParam().parse("ilore", "primordial", ctx());
        Pattern pattern = (Pattern) ((QueryPredicate.Eq) ((QueryPredicate.Or) predicate)
                .predicates().getFirst()).value();
        assertThat(pattern.matcher("Forged in the Primordial Court").find()).isTrue();
        assertThat(pattern.matcher("A PRIMORDIAL WEAPON").find()).isTrue();
        assertThat(pattern.matcher("just a plain stick").find()).isFalse();
    }

    @Test
    void regexMetacharactersQuoted() throws Exception {
        // e.g. lore contains "+5 Sharpness" — the + shouldn't be treated
        // as a quantifier.
        QueryPredicate predicate = new ItemLoreParam().parse("ilore", "+5", ctx());
        Pattern pattern = (Pattern) ((QueryPredicate.Eq) ((QueryPredicate.Or) predicate)
                .predicates().getFirst()).value();
        assertThat(pattern.matcher("attack +5").find()).isTrue();
        assertThat(pattern.matcher("5").find()).isFalse();
    }

    @Test
    void blankValueRejected() {
        ItemLoreParam param = new ItemLoreParam();
        assertThatThrownBy(() -> param.parse("ilore", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("ilore", "   ", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("ilore", null, ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void aliasesCoverBothShortAndLong() {
        // {@code d} is restored from v1's {@code ItemDescParameter}.
        assertThat(new ItemLoreParam().aliases()).containsExactly("ilore", "itemlore", "d");
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
