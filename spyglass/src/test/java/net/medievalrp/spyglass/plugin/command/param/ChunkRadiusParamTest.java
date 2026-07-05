package net.medievalrp.spyglass.plugin.command.param;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;

class ChunkRadiusParamTest {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final BlockLocation ORIGIN = new BlockLocation(WORLD, "world", 100, 64, -50);

    @Test
    void singleChunkCoversOriginChunkFullHeight() {
        QueryPredicate predicate = ChunkRadiusParam.boxAround(ORIGIN, 1, 1000, -64, 319);

        assertThat(predicate).isInstanceOf(QueryPredicate.And.class);
        QueryPredicate.And and = (QueryPredicate.And) predicate;
        assertThat(and.predicates()).hasSize(4);

        QueryPredicate.Eq worldEq = (QueryPredicate.Eq) and.predicates().get(0);
        assertThat(worldEq.field()).isEqualTo("location.worldId");
        assertThat(worldEq.value()).isEqualTo(WORLD);

        assertRange(and.predicates().get(1), "location.x", 96, 111);
        assertRange(and.predicates().get(2), "location.y", -64, 319);
        assertRange(and.predicates().get(3), "location.z", -64, -49);
    }

    @Test
    void expandsOneChunkOutwardForCrTwo() {
        QueryPredicate predicate = ChunkRadiusParam.boxAround(ORIGIN, 2, 1000, -64, 319);
        QueryPredicate.And and = (QueryPredicate.And) predicate;
        assertRange(and.predicates().get(1), "location.x", 80, 127);
        assertRange(and.predicates().get(3), "location.z", -80, -33);
    }

    @Test
    void clampsChunkReachToBlockCap() {
        QueryPredicate predicate = ChunkRadiusParam.boxAround(ORIGIN, 5, 16, -64, 319);
        QueryPredicate.And and = (QueryPredicate.And) predicate;
        assertRange(and.predicates().get(1), "location.x", 80, 127);
        assertRange(and.predicates().get(3), "location.z", -80, -33);
    }

    @Test
    void unlocatedSenderRejected() {
        assertThatThrownBy(() -> new ChunkRadiusParam().parse("cr", "1", ctx(null, 1000)))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("located sender");
    }

    @Test
    void nonNumericRejected() {
        assertThatThrownBy(() -> new ChunkRadiusParam().parse("cr", "big", ctx(ORIGIN, 1000)))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("must be a number");
    }

    @Test
    void zeroRejected() {
        assertThatThrownBy(() -> new ChunkRadiusParam().parse("cr", "0", ctx(ORIGIN, 1000)))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    void suppressesDefaultRadiusContract() {
        ChunkRadiusParam param = new ChunkRadiusParam();
        assertThat(param.suppressesDefaultRadius("cr")).isTrue();
        assertThat(param.suppressesDefaultRadius("chunkradius")).isTrue();
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
