package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

class IpParamTest {

    @Test
    void producesEqOnAddressField() throws Exception {
        QueryPredicate predicate = new IpParam().parse("ip", "10.0.0.1", ctx());
        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
        assertThat(eq.field()).isEqualTo("address");
        assertThat(eq.value()).isEqualTo("10.0.0.1");
    }

    @Test
    void trimsWhitespace() throws Exception {
        QueryPredicate predicate = new IpParam().parse("ip", "  10.0.0.1  ", ctx());
        assertThat(((QueryPredicate.Eq) predicate).value()).isEqualTo("10.0.0.1");
    }

    @Test
    void emptyValueRejected() {
        assertThatThrownBy(() -> new IpParam().parse("ip", "", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
