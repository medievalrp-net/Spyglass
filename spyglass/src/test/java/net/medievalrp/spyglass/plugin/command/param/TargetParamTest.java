package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

class TargetParamTest {

    @Test
    void producesCaseInsensitiveRegexEq() throws Exception {
        QueryPredicate predicate = new TargetParam().parse("trg", "CHEST", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
        assertThat(eq.field()).isEqualTo("target");
        assertThat(eq.value()).isInstanceOf(Pattern.class);
        Pattern pattern = (Pattern) eq.value();
        assertThat(pattern.matcher("trapped_chest").find()).isTrue();
    }

    @Test
    void quotesRegexMetacharacters() throws Exception {
        // Input with regex metacharacters must be treated as literal.
        QueryPredicate predicate = new TargetParam().parse("trg", "a.b", ctx());
        Pattern pattern = (Pattern) ((QueryPredicate.Eq) predicate).value();
        assertThat(pattern.matcher("a.b").find()).isTrue();
        assertThat(pattern.matcher("axb").find()).isFalse();
    }

    @Test
    void emptyValueRejected() {
        assertThatThrownBy(() -> new TargetParam().parse("trg", "", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    // trg:x,y,z is the coordinate form COMMANDS.md documents (#305):
    // point ranges on location.*, so the parser's location-bound
    // detection suppresses the default radius.
    @Test
    void coordinateFormPinsTheCell() throws Exception {
        QueryPredicate predicate = new TargetParam().parse("trg", "100,-60,200", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.And.class);
        var parts = ((QueryPredicate.And) predicate).predicates();
        assertThat(parts).containsExactly(
                new QueryPredicate.Range("location.x", 100, 100),
                new QueryPredicate.Range("location.y", -60, -60),
                new QueryPredicate.Range("location.z", 200, 200));
    }

    @Test
    void coordinateFormToleratesSpacesAfterCommas() throws Exception {
        QueryPredicate predicate = new TargetParam().parse("trg", "1, -2, 3", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.And.class);
    }

    @Test
    void nonNumericTripleStaysASubstringMatch() throws Exception {
        // A material-ish value with commas must not be misread as coords.
        QueryPredicate predicate = new TargetParam().parse("trg", "chest,barrel", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
