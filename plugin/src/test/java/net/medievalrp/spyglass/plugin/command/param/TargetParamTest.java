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

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
