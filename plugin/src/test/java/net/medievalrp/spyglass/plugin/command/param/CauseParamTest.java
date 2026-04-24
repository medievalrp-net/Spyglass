package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

class CauseParamTest {

    @Test
    void singleCauseProducesOrAcrossDescriptionAndEntityType() throws Exception {
        QueryPredicate predicate = new CauseParam().parse("c", "creeper", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        QueryPredicate.Or or = (QueryPredicate.Or) predicate;
        assertThat(or.predicates()).hasSize(2);
        assertThat(or.predicates().getFirst()).isInstanceOf(QueryPredicate.Eq.class);
        QueryPredicate.Eq firstEq = (QueryPredicate.Eq) or.predicates().getFirst();
        assertThat(firstEq.field()).isEqualTo("source.description");
        assertThat(firstEq.value()).isEqualTo("creeper");
    }

    @Test
    void multipleIncludesUsesIn() throws Exception {
        QueryPredicate predicate = new CauseParam().parse("c", "creeper,zombie,skeleton", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Or.class);
        QueryPredicate.Or or = (QueryPredicate.Or) predicate;
        assertThat(or.predicates()).hasSize(2);
        for (QueryPredicate clause : or.predicates()) {
            assertThat(clause).isInstanceOf(QueryPredicate.In.class);
            assertThat(((QueryPredicate.In) clause).values()).hasSize(3);
        }
    }

    @Test
    void excludeProducesAndNotWrapping() throws Exception {
        QueryPredicate predicate = new CauseParam().parse("c", "creeper,!baby_zombie", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.And.class);
        QueryPredicate.And and = (QueryPredicate.And) predicate;
        assertThat(and.predicates()).hasSize(2);
        assertThat(and.predicates().get(0)).isInstanceOf(QueryPredicate.Or.class);
        assertThat(and.predicates().get(1)).isInstanceOf(QueryPredicate.Not.class);
    }

    @Test
    void onlyExcludeProducesSingleNot() throws Exception {
        QueryPredicate predicate = new CauseParam().parse("c", "!environment", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Not.class);
    }

    @Test
    void emptyValueRejected() {
        assertThatThrownBy(() -> new CauseParam().parse("c", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> new CauseParam().parse("c", ",", ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
