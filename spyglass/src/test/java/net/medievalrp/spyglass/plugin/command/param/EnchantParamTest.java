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
 * {@link EnchantParam} unit tests. Enchants are persisted as {@code
 * "key=level"} strings in the StoredItem's {@code enchants} list (e.g.
 * {@code "sharpness=5"}), so our pattern is still a substring match, but
 * we first normalize the input:
 * <ul>
 *   <li>{@code ench:sharpness:5} → {@code sharpness=5} (colon→equals)</li>
 *   <li>mixed case lowercased</li>
 * </ul>
 * This gives operators three equivalent spellings — all must flatten
 * to the same underlying regex.
 */
class EnchantParamTest {

    private static final List<String> EXPECTED_PATHS = List.of(
            "item.enchants",
            "result.enchants",
            "beforeItem.enchants",
            "afterItem.enchants",
            "originalBlock.containerItems.enchants",
            "newBlock.containerItems.enchants");

    @Test
    void producesOrAcrossAllItemEnchantPaths() throws Exception {
        QueryPredicate predicate = new EnchantParam().parse("ench", "sharpness", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        QueryPredicate.Or or = (QueryPredicate.Or) predicate;
        assertThat(or.predicates()).hasSize(6);
        List<String> fields = or.predicates().stream()
                .map(p -> ((QueryPredicate.Eq) p).field())
                .toList();
        assertThat(fields).containsExactlyElementsOf(EXPECTED_PATHS);
    }

    @Test
    void nameOnlyMatchesAnyLevel() throws Exception {
        // "sharpness" with no level must match any persisted
        // "sharpness=N" string.
        QueryPredicate predicate = new EnchantParam().parse("ench", "sharpness", ctx());
        Pattern pattern = firstPattern(predicate);
        assertThat(pattern.matcher("sharpness=5").find()).isTrue();
        assertThat(pattern.matcher("sharpness=1").find()).isTrue();
        assertThat(pattern.matcher("smite=5").find()).isFalse();
    }

    @Test
    void nameEqualsLevelMatchesExact() throws Exception {
        QueryPredicate predicate = new EnchantParam().parse("ench", "sharpness=5", ctx());
        Pattern pattern = firstPattern(predicate);
        assertThat(pattern.matcher("sharpness=5").find()).isTrue();
        // substring match — "sharpness=50" would also match "sharpness=5";
        // operators can use name-only if they want any level, or a more
        // specific regex via a different mechanism. This matches the v1
        // behavior — document it here so it doesn't get "fixed" by accident.
        assertThat(pattern.matcher("sharpness=50").find()).isTrue();
        assertThat(pattern.matcher("sharpness=4").find()).isFalse();
    }

    @Test
    void colonLevelNormalizesToEquals() throws Exception {
        // ench:sharpness:5 is an alternative spelling of ench:sharpness=5.
        // Both must compile to patterns that match the same persisted value.
        QueryPredicate colon = new EnchantParam().parse("ench", "sharpness:5", ctx());
        QueryPredicate equals = new EnchantParam().parse("ench", "sharpness=5", ctx());
        assertThat(firstPattern(colon).pattern()).isEqualTo(firstPattern(equals).pattern());
    }

    @Test
    void mixedCaseNormalisedToLowerCase() throws Exception {
        QueryPredicate predicate = new EnchantParam().parse("ench", "SHARPNESS=5", ctx());
        Pattern pattern = firstPattern(predicate);
        // The persisted form is always lowercase; the normalised pattern
        // must match the lowercase form regardless of input casing.
        assertThat(pattern.matcher("sharpness=5").find()).isTrue();
    }

    @Test
    void blankValueRejected() {
        EnchantParam param = new EnchantParam();
        assertThatThrownBy(() -> param.parse("ench", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("ench", "   ", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("ench", null, ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void aliasesExposeShortAndIPrefixedForms() {
        // ienchant/ienchantments join the i-prefixed item param family
        // (iname/ilore/itags) for discoverability (#140).
        assertThat(new EnchantParam().aliases())
                .containsExactly("ench", "enchant", "enchantment", "ienchant", "ienchantments");
    }

    @Test
    void iPrefixedAliasParsesIdentically() throws Exception {
        // the new alias must resolve to the same predicate shape as ench:
        QueryPredicate predicate = new EnchantParam().parse("ienchantments", "sharpness", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        List<String> fields = ((QueryPredicate.Or) predicate).predicates().stream()
                .map(p -> ((QueryPredicate.Eq) p).field())
                .toList();
        assertThat(fields).containsExactlyElementsOf(EXPECTED_PATHS);
    }

    private static Pattern firstPattern(QueryPredicate predicate) {
        return (Pattern) ((QueryPredicate.Eq) ((QueryPredicate.Or) predicate)
                .predicates().getFirst()).value();
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
