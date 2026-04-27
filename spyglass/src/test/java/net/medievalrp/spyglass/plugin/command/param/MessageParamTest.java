package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

class MessageParamTest {

    @Test
    void producesOrAcrossMessageAndCommandLine() throws Exception {
        QueryPredicate predicate = new MessageParam().parse("m", "diamond", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        QueryPredicate.Or or = (QueryPredicate.Or) predicate;
        assertThat(or.predicates()).hasSize(2);

        QueryPredicate first = or.predicates().get(0);
        QueryPredicate second = or.predicates().get(1);
        assertThat(first).isInstanceOf(QueryPredicate.Eq.class);
        assertThat(second).isInstanceOf(QueryPredicate.Eq.class);

        assertThat(((QueryPredicate.Eq) first).field()).isEqualTo("message");
        assertThat(((QueryPredicate.Eq) second).field()).isEqualTo("commandLine");

        Object fv = ((QueryPredicate.Eq) first).value();
        assertThat(fv).isInstanceOf(Pattern.class);
        assertThat(((Pattern) fv).pattern()).contains("diamond");
    }

    @Test
    void emptyValueRejected() {
        assertThatThrownBy(() -> new MessageParam().parse("m", "  ", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
