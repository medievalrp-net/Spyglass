package net.medievalrp.omniscience2.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.Pattern;
import net.medievalrp.omniscience2.api.param.ParamParseException;
import net.medievalrp.omniscience2.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.omniscience2.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

/**
 * {@link ItemNameParam} unit tests. The handler expands into a 5-clause
 * {@link QueryPredicate.Or} because StoredItem can land at five different
 * Mongo paths depending on the event (container deposit/withdraw, item
 * drop/pickup, block breaks that release a chest's contents, etc.). If a
 * new record type adds a sixth item-bearing field and ItemFieldParams is
 * not updated, that field's items will silently be un-searchable by
 * {@code iname:}.
 *
 * <p>These tests pin the current five-path shape so a future ItemFieldParams
 * change is loud, not silent, and verify the regex-quote path so that
 * {@code iname:Sword+} doesn't get interpreted as a regex quantifier.
 */
class ItemNameParamTest {

    private static final List<String> EXPECTED_PATHS = List.of(
            "item.name",
            "beforeItem.name",
            "afterItem.name",
            "originalBlock.containerItems.name",
            "newBlock.containerItems.name");

    @Test
    void producesOrAcrossAllFiveItemPaths() throws Exception {
        QueryPredicate predicate = new ItemNameParam().parse("iname", "Excalibur", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        QueryPredicate.Or or = (QueryPredicate.Or) predicate;
        assertThat(or.predicates()).hasSize(5);

        List<String> fields = or.predicates().stream()
                .map(p -> ((QueryPredicate.Eq) p).field())
                .toList();
        assertThat(fields).containsExactlyElementsOf(EXPECTED_PATHS);
    }

    @Test
    void patternIsCaseInsensitive() throws Exception {
        QueryPredicate predicate = new ItemNameParam().parse("iname", "sword", ctx());
        Pattern pattern = (Pattern) ((QueryPredicate.Eq) ((QueryPredicate.Or) predicate)
                .predicates().getFirst()).value();
        assertThat(pattern.flags() & Pattern.CASE_INSENSITIVE).isNotZero();
        // Literal match: the quoted pattern wrapped as case-insensitive
        // should match mixed-case strings directly.
        assertThat(pattern.matcher("Legendary Sword of Light").find()).isTrue();
        assertThat(pattern.matcher("SWORD").find()).isTrue();
    }

    @Test
    void regexMetacharactersQuoted() throws Exception {
        // "Sword+" is valid user input; if we didn't quote it, the + would
        // mean "one or more preceding 'd'". Test by building a pattern
        // that shouldn't match "Swor" (which a regex-interpreted pattern
        // would partially match).
        QueryPredicate predicate = new ItemNameParam().parse("iname", "Sword+", ctx());
        Pattern pattern = (Pattern) ((QueryPredicate.Eq) ((QueryPredicate.Or) predicate)
                .predicates().getFirst()).value();
        assertThat(pattern.matcher("Old Sword+").find()).isTrue();
        assertThat(pattern.matcher("Plain Sword").find()).isFalse();
    }

    @Test
    void inputTrimmedBeforePatternCompile() throws Exception {
        QueryPredicate predicate = new ItemNameParam().parse("iname", "  knife  ", ctx());
        Pattern pattern = (Pattern) ((QueryPredicate.Eq) ((QueryPredicate.Or) predicate)
                .predicates().getFirst()).value();
        // Trimming means "knife" (not "  knife  "), so a bare "knife"
        // string should match.
        assertThat(pattern.matcher("the knife").find()).isTrue();
    }

    @Test
    void blankValueRejected() {
        ItemNameParam param = new ItemNameParam();
        assertThatThrownBy(() -> param.parse("iname", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("iname", "   ", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("iname", null, ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void aliasesCoverBothShortAndLong() {
        assertThat(new ItemNameParam().aliases()).containsExactly("iname", "itemname");
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
