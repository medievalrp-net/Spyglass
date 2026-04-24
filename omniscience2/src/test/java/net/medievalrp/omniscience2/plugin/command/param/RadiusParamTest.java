package net.medievalrp.omniscience2.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.medievalrp.omniscience2.api.param.ParamParseException;
import net.medievalrp.omniscience2.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.omniscience2.api.query.QueryPredicate;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import org.junit.jupiter.api.Test;

/**
 * {@link RadiusParam} unit tests. The param is pure: every input it
 * touches (origin + maxRadius) comes from {@link ParamContext}, which is
 * a plain record — no Bukkit static in the hot path. We cover
 * (a) the shape of the emitted predicate (world Eq + three Range clauses),
 * (b) clamping to configured maxRadius,
 * (c) rejection when the sender isn't located (e.g. console),
 * (d) integer parsing and positivity checks,
 * (e) the {@code suppressesDefaultRadius} contract that prevents the
 *     default-radius injector from double-applying.
 */
class RadiusParamTest {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final BlockLocation ORIGIN = new BlockLocation(WORLD, "world", 100, 64, -50);

    @Test
    void producesAndOfWorldPlusThreeAxisRanges() throws Exception {
        QueryPredicate predicate = new RadiusParam().parse("r", "10", ctx(ORIGIN, 1000));

        assertThat(predicate).isInstanceOf(QueryPredicate.And.class);
        QueryPredicate.And and = (QueryPredicate.And) predicate;
        assertThat(and.predicates()).hasSize(4);

        QueryPredicate world = and.predicates().get(0);
        assertThat(world).isInstanceOf(QueryPredicate.Eq.class);
        QueryPredicate.Eq worldEq = (QueryPredicate.Eq) world;
        assertThat(worldEq.field()).isEqualTo("location.worldId");
        assertThat(worldEq.value()).isEqualTo(WORLD);

        // Axes must be location.x/y/z in order; each must be an
        // Range with the origin±radius bounds.
        assertRange(and.predicates().get(1), "location.x", 90, 110);
        assertRange(and.predicates().get(2), "location.y", 54, 74);
        assertRange(and.predicates().get(3), "location.z", -60, -40);
    }

    @Test
    void clampsToMaxRadius() throws Exception {
        // Operator requests radius=500 but config cap is 50 — the predicate
        // must clamp silently, not reject. A reject would break /co near 500
        // with the same message users saw for max-radius-zero.
        QueryPredicate predicate = new RadiusParam().parse("r", "500", ctx(ORIGIN, 50));
        QueryPredicate.Range xRange = (QueryPredicate.Range) ((QueryPredicate.And) predicate)
                .predicates().get(1);
        assertThat(xRange.lowerInclusive()).isEqualTo(50);
        assertThat(xRange.upperInclusive()).isEqualTo(150);
    }

    @Test
    void radiusSmallerThanCapNotInflated() throws Exception {
        // Symmetric to clampsToMaxRadius — radius=5 under a cap of 100
        // must stay 5, not get pushed up to the cap.
        QueryPredicate predicate = new RadiusParam().parse("r", "5", ctx(ORIGIN, 100));
        QueryPredicate.Range xRange = (QueryPredicate.Range) ((QueryPredicate.And) predicate)
                .predicates().get(1);
        assertThat(xRange.lowerInclusive()).isEqualTo(95);
        assertThat(xRange.upperInclusive()).isEqualTo(105);
    }

    @Test
    void unlocatedSenderRejected() {
        // Console (no location) must be told to use -g or explicit
        // coords instead of silently picking some default world.
        assertThatThrownBy(() -> new RadiusParam().parse("r", "10", ctx(null, 100)))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("located sender");
    }

    @Test
    void nonNumericRejected() {
        assertThatThrownBy(() -> new RadiusParam().parse("r", "big", ctx(ORIGIN, 100)))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("must be a number");
    }

    @Test
    void zeroRejected() {
        // r:0 would produce a zero-volume box that matches nothing.
        // Safer to reject and let the operator supply -g.
        assertThatThrownBy(() -> new RadiusParam().parse("r", "0", ctx(ORIGIN, 100)))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void negativeRejected() {
        assertThatThrownBy(() -> new RadiusParam().parse("r", "-5", ctx(ORIGIN, 100)))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void suppressesDefaultRadiusContract() {
        // Both aliases must return true so the QueryStringParser's
        // default-radius injector skips when either is present.
        RadiusParam param = new RadiusParam();
        assertThat(param.suppressesDefaultRadius("r")).isTrue();
        assertThat(param.suppressesDefaultRadius("radius")).isTrue();
    }

    @Test
    void groupAroundHelperMatchesParsedPredicate() {
        // The static helper is used by the default-radius injector in
        // QueryStringParser; its shape must match what parse() produces
        // so grouping works the same whether the operator typed r:N or
        // relied on the default.
        QueryPredicate helper = RadiusParam.groupAround(ORIGIN, 10);
        assertThat(helper).isInstanceOf(QueryPredicate.And.class);
        QueryPredicate.And and = (QueryPredicate.And) helper;
        assertThat(and.predicates()).hasSize(4);
        assertThat(and.predicates().get(0)).isInstanceOf(QueryPredicate.Eq.class);
    }

    private static void assertRange(QueryPredicate p, String field, int lower, int upper) {
        assertThat(p).isInstanceOf(QueryPredicate.Range.class);
        QueryPredicate.Range range = (QueryPredicate.Range) p;
        assertThat(range.field()).isEqualTo(field);
        assertThat(range.lowerInclusive()).isEqualTo(lower);
        assertThat(range.upperInclusive()).isEqualTo(upper);
    }

    private static ParamContext ctx(BlockLocation origin, int maxRadius) {
        return new ParamContext(null, origin, maxRadius);
    }
}
