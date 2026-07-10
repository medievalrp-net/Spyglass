package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.junit.jupiter.api.Test;

/**
 * #264: the live-state guard needs the materials the operator excluded
 * with block:!x. The extraction walks Not subtrees for Eq/In over the
 * target/containerType fields, so it reads both the historical b: shape
 * and the container-aware #263 shape, and ignores every other param's
 * negations.
 */
class ExcludedMaterialsTest {

    @Test
    void readsTheHistoricalTargetOnlyShape() {
        assertThat(ExcludedMaterials.of(List.of(
                new QueryPredicate.Not(new QueryPredicate.Eq("target", "CHEST")))))
                .containsExactly("CHEST");
        assertThat(ExcludedMaterials.of(List.of(
                new QueryPredicate.Not(new QueryPredicate.In("target", List.of("CHEST", "BARREL"))))))
                .containsExactlyInAnyOrder("CHEST", "BARREL");
    }

    @Test
    void readsTheContainerAwareShape() {
        QueryPredicate shape = new QueryPredicate.Not(new QueryPredicate.Or(List.of(
                new QueryPredicate.And(List.of(
                        new QueryPredicate.Exists("containerType", true),
                        new QueryPredicate.Eq("containerType", "CHEST"))),
                new QueryPredicate.And(List.of(
                        new QueryPredicate.Exists("containerType", false),
                        new QueryPredicate.Eq("target", "CHEST"))))));
        assertThat(ExcludedMaterials.of(List.of(shape))).containsExactly("CHEST");
    }

    @Test
    void ignoresIncludesAndOtherParamsNegations() {
        assertThat(ExcludedMaterials.of(List.of(
                new QueryPredicate.Eq("target", "CHEST"),
                new QueryPredicate.Not(new QueryPredicate.Eq("event", "PLACE")),
                new QueryPredicate.Not(new QueryPredicate.Eq("source.playerId", "someone")),
                new QueryPredicate.Range("occurred", 1L, 2L))))
                .isEmpty();
    }

    @Test
    void mixedIncludeExcludeOnlyYieldsTheExcludes() {
        // b:dirt,!chest compiles to And(include(dirt), Not(exclude(chest))).
        assertThat(ExcludedMaterials.of(List.of(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("target", "DIRT"),
                new QueryPredicate.Not(new QueryPredicate.Eq("target", "CHEST")))))))
                .containsExactly("CHEST");
    }
}
