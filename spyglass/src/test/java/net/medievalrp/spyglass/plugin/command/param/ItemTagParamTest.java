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
 * {@link ItemTagParam} unit tests. Structurally mirrors
 * {@link ItemLoreParamTest} (same five-path OR expansion), but the sub-field
 * is {@code tags} (the item's {@code minecraft:custom_data} projection). The
 * stored value is a single string, so Mongo regex matches it like {@code name}
 * rather than needing per-element array semantics.
 */
class ItemTagParamTest {

    private static final List<String> EXPECTED_PATHS = List.of(
            "item.tags",
            "beforeItem.tags",
            "afterItem.tags",
            "originalBlock.containerItems.tags",
            "newBlock.containerItems.tags");

    @Test
    void producesOrAcrossAllFiveTagPaths() throws Exception {
        QueryPredicate predicate = new ItemTagParam().parse("itags", "quest", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        QueryPredicate.Or or = (QueryPredicate.Or) predicate;
        assertThat(or.predicates()).hasSize(5);
        List<String> fields = or.predicates().stream()
                .map(p -> ((QueryPredicate.Eq) p).field())
                .toList();
        assertThat(fields).containsExactlyElementsOf(EXPECTED_PATHS);
    }

    @Test
    void patternMatchesCustomDataSubstrings() throws Exception {
        QueryPredicate predicate = new ItemTagParam().parse("itags", "deliver_letter", ctx());
        Pattern pattern = (Pattern) ((QueryPredicate.Eq) ((QueryPredicate.Or) predicate)
                .predicates().getFirst()).value();
        assertThat(pattern.matcher("{quest:\"deliver_letter\",stage:2}").find()).isTrue();
        assertThat(pattern.matcher("{quest:\"DELIVER_LETTER\"}").find()).isTrue();
        assertThat(pattern.matcher("{quest:\"slay_dragon\"}").find()).isFalse();
    }

    @Test
    void matchesPluginNamespacedKeys() throws Exception {
        // operators search PDC keys like "mmoitems:type" verbatim
        QueryPredicate predicate = new ItemTagParam().parse("itags", "mmoitems:type", ctx());
        Pattern pattern = (Pattern) ((QueryPredicate.Eq) ((QueryPredicate.Or) predicate)
                .predicates().getFirst()).value();
        assertThat(pattern.matcher(
                "{PublicBukkitValues:{\"mmoitems:type\":\"SWORD\"}}").find()).isTrue();
    }

    @Test
    void regexMetacharactersQuoted() throws Exception {
        QueryPredicate predicate = new ItemTagParam().parse("itags", "a{b", ctx());
        Pattern pattern = (Pattern) ((QueryPredicate.Eq) ((QueryPredicate.Or) predicate)
                .predicates().getFirst()).value();
        assertThat(pattern.matcher("xa{by").find()).isTrue();
    }

    @Test
    void blankValueRejected() {
        ItemTagParam param = new ItemTagParam();
        assertThatThrownBy(() -> param.parse("itags", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("itags", "   ", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("itags", null, ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void aliasesCoverShortAndLong() {
        assertThat(new ItemTagParam().aliases()).containsExactly("itags", "itag");
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
