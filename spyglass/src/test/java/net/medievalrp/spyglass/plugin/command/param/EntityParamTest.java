package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

/**
 * {@link EntityParam} unit tests. EntityType is a stateless enum so no
 * Bukkit boot is required. Covers upper-casing normalization (so
 * {@code e:creeper} and {@code e:CREEPER} are equivalent), the
 * single/multi split, and the unknown-type rejection path that prevents
 * typos from silently returning no results.
 */
class EntityParamTest {

    @Test
    void singleEntityProducesEq() throws Exception {
        QueryPredicate predicate = new EntityParam().parse("e", "creeper", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.Eq.class);
        QueryPredicate.Eq eq = (QueryPredicate.Eq) predicate;
        assertThat(eq.field()).isEqualTo("target");
        assertThat(eq.value()).isEqualTo("CREEPER");
    }

    @Test
    void inputUpperCasedBeforeEnumLookup() throws Exception {
        // "ZoMbIe" is valid input — EntityType.valueOf needs "ZOMBIE".
        QueryPredicate predicate = new EntityParam().parse("e", "ZoMbIe", ctx());

        assertThat(((QueryPredicate.Eq) predicate).value()).isEqualTo("ZOMBIE");
    }

    @Test
    void multipleEntitiesProduceIn() throws Exception {
        QueryPredicate predicate = new EntityParam().parse("e", "zombie,skeleton,creeper", ctx());

        assertThat(predicate).isInstanceOf(QueryPredicate.In.class);
        QueryPredicate.In in = (QueryPredicate.In) predicate;
        assertThat(in.field()).isEqualTo("target");
        assertThat(in.values().toArray()).containsExactly("ZOMBIE", "SKELETON", "CREEPER");
    }

    @Test
    void unknownTypeRejected() {
        assertThatThrownBy(() -> new EntityParam().parse("e", "pizza_monster", ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("Unknown entity type");
    }

    @Test
    void mixedValidAndInvalidRejectsOnFirstBad() {
        // Fails fast; we don't accumulate partial success and silently drop
        // the bad entry, because that would let typos pass through.
        assertThatThrownBy(() -> new EntityParam().parse("e", "zombie,not_a_thing", ctx()))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("not_a_thing");
    }

    @Test
    void blankValueRejected() {
        EntityParam param = new EntityParam();
        assertThatThrownBy(() -> param.parse("e", "", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("e", "   ", ctx()))
                .isInstanceOf(ParamParseException.class);
        assertThatThrownBy(() -> param.parse("e", null, ctx()))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void aliasesReturnBothShortAndLong() {
        assertThat(new EntityParam().aliases()).containsExactly("e", "entity");
    }

    private static ParamContext ctx() {
        return new ParamContext(null, null, 100);
    }
}
