package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

/**
 * Pins the container-aware predicate shape b: compiles to (#263). The full
 * parse path needs a live registry (see BlockParamTest's coverage note);
 * membership() is pure and carries the semantics: containerType where the
 * record has one, target otherwise, with Exists guards on BOTH branches so
 * a Not() of the shape survives SQL three-valued logic over the nullable
 * containerType column.
 */
class BlockParamShapeTest {

    @Test
    void singleMaterialCompilesToGuardedContainerAwareOr() {
        QueryPredicate shape = BlockParam.membership(List.of("CHEST"));
        assertThat(shape).isEqualTo(new QueryPredicate.Or(List.of(
                new QueryPredicate.And(List.of(
                        new QueryPredicate.Exists("containerType", true),
                        new QueryPredicate.Eq("containerType", "CHEST"))),
                new QueryPredicate.And(List.of(
                        new QueryPredicate.Exists("containerType", false),
                        new QueryPredicate.Eq("target", "CHEST"))))));
    }

    @Test
    void multipleMaterialsUseInOnBothBranches() {
        QueryPredicate shape = BlockParam.membership(List.of("CHEST", "BARREL"));
        assertThat(shape).isEqualTo(new QueryPredicate.Or(List.of(
                new QueryPredicate.And(List.of(
                        new QueryPredicate.Exists("containerType", true),
                        new QueryPredicate.In("containerType", List.of("CHEST", "BARREL")))),
                new QueryPredicate.And(List.of(
                        new QueryPredicate.Exists("containerType", false),
                        new QueryPredicate.In("target", List.of("CHEST", "BARREL")))))));
    }
}
