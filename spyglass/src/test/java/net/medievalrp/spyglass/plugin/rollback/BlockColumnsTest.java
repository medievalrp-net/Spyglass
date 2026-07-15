package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for the columnar rollback store (#67). */
class BlockColumnsTest {

    @Test
    void internDedupesEqualStringsAndHandlesNull() {
        BlockColumns cols = new BlockColumns(16);
        int stone = cols.intern("minecraft:stone");
        int air = cols.intern("minecraft:air");
        assertThat(cols.intern("minecraft:stone")).isEqualTo(stone);
        assertThat(cols.intern("minecraft:air")).isEqualTo(air);
        assertThat(stone).isNotEqualTo(air);
        assertThat(cols.intern(null)).isEqualTo(-1);
        // Two distinct strings interned, regardless of how many lookups.
        assertThat(cols.paletteSize()).isEqualTo(2);
    }

    @Test
    void addStoresCellsAndResolvesPalette() {
        BlockColumns cols = new BlockColumns(2);
        int stone = cols.intern("minecraft:stone");
        int air = cols.intern("minecraft:air");
        cols.add(10, 64, 20, stone, air);
        cols.add(11, 65, 21, air, -1); // -1 expected == no check

        assertThat(cols.count()).isEqualTo(2);
        assertThat(cols.x(0)).isEqualTo(10);
        assertThat(cols.y(0)).isEqualTo(64);
        assertThat(cols.z(0)).isEqualTo(20);
        assertThat(cols.replData(0)).isEqualTo("minecraft:stone");
        assertThat(cols.expData(0)).isEqualTo("minecraft:air");
        assertThat(cols.replData(1)).isEqualTo("minecraft:air");
        assertThat(cols.expData(1)).isNull();
    }

    @Test
    void growsBeyondInitialCapacity() {
        BlockColumns cols = new BlockColumns(2);
        int id = cols.intern("minecraft:stone");
        for (int i = 0; i < 100; i++) {
            cols.add(i, 64, 0, id, -1);
        }
        assertThat(cols.count()).isEqualTo(100);
        assertThat(cols.x(99)).isEqualTo(99);
        assertThat(cols.replData(99)).isEqualTo("minecraft:stone");
    }

    @Test
    void tracksMinMaxBounds() {
        BlockColumns cols = new BlockColumns(8);
        int id = cols.intern("minecraft:stone");
        cols.add(5, 70, -3, id, -1);
        cols.add(-2, 64, 9, id, -1);
        cols.add(8, 80, 4, id, -1);
        assertThat(cols.minX()).isEqualTo(-2);
        assertThat(cols.minY()).isEqualTo(64);
        assertThat(cols.minZ()).isEqualTo(-3);
        assertThat(cols.maxX()).isEqualTo(8);
        assertThat(cols.maxY()).isEqualTo(80);
        assertThat(cols.maxZ()).isEqualTo(9);
    }

    @Test
    void chunkSortedOrderGroupsByChunkThenAscendingY() {
        BlockColumns cols = new BlockColumns(8);
        int id = cols.intern("minecraft:stone");
        // Two chunks: (0,0) and (1,0). Insert out of order, high Y first.
        cols.add(2, 100, 2, id, -1);   // chunk (0,0)
        cols.add(20, 64, 2, id, -1);   // chunk (1,0)
        cols.add(3, 50, 3, id, -1);    // chunk (0,0), lower Y
        cols.add(21, 200, 5, id, -1);  // chunk (1,0), higher Y

        int[] order = cols.chunkSortedOrder();
        assertThat(order).hasSize(4);
        // Chunk (0,0) cells first, ascending Y (the y=50 cell then y=100).
        assertThat(cols.x(order[0]) >> 4).isEqualTo(0);
        assertThat(cols.x(order[1]) >> 4).isEqualTo(0);
        assertThat(cols.y(order[0])).isLessThanOrEqualTo(cols.y(order[1]));
        // Then chunk (1,0), ascending Y.
        assertThat(cols.x(order[2]) >> 4).isEqualTo(1);
        assertThat(cols.x(order[3]) >> 4).isEqualTo(1);
        assertThat(cols.y(order[2])).isLessThanOrEqualTo(cols.y(order[3]));
    }

    @Test
    void chunkSortedOrderHandlesEmptyAndSingle() {
        assertThat(new BlockColumns(4).chunkSortedOrder()).isEmpty();
        BlockColumns one = new BlockColumns(4);
        one.add(1, 2, 3, one.intern("minecraft:stone"), -1);
        assertThat(one.chunkSortedOrder()).containsExactly(0);
    }

    // --- per-cell coalescing (#321) ---

    @Test
    void addOrReplaceFoldsRepeatCellsToTheLastWrite() {
        BlockColumns cols = new BlockColumns(8);
        int dirt = cols.intern("minecraft:dirt");
        int air = cols.intern("minecraft:air");
        // place, break, place at one cell, rollback direction: the
        // stream's writes are air (newest place), dirt (break), air
        // (oldest place). Net write = the last arrival = air.
        assertThat(cols.addOrReplace(4, 64, 4, air, dirt)).isTrue();
        assertThat(cols.addOrReplace(4, 64, 4, dirt, air)).isFalse();
        assertThat(cols.addOrReplace(4, 64, 4, air, dirt)).isFalse();

        assertThat(cols.count()).isEqualTo(1);
        assertThat(cols.replData(0)).isEqualTo("minecraft:air");
        // First arrival's expected-current survives the folds.
        assertThat(cols.expData(0)).isEqualTo("minecraft:dirt");
    }

    @Test
    void addOrReplaceKeepsDistinctCellsApart() {
        BlockColumns cols = new BlockColumns(8);
        int stone = cols.intern("minecraft:stone");
        int air = cols.intern("minecraft:air");
        assertThat(cols.addOrReplace(0, 64, 0, stone, -1)).isTrue();
        assertThat(cols.addOrReplace(0, 65, 0, air, -1)).isTrue();
        assertThat(cols.addOrReplace(0, 64, 1, air, -1)).isTrue();
        assertThat(cols.addOrReplace(1, 64, 0, air, -1)).isTrue();
        assertThat(cols.count()).isEqualTo(4);
        assertThat(cols.replData(0)).isEqualTo("minecraft:stone");
    }

    @Test
    void addOrReplaceDoesNotWidenBoundsOnAFold() {
        BlockColumns cols = new BlockColumns(8);
        int id = cols.intern("minecraft:stone");
        cols.addOrReplace(5, 70, -3, id, -1);
        cols.addOrReplace(5, 70, -3, id, -1);
        assertThat(cols.minX()).isEqualTo(5);
        assertThat(cols.maxX()).isEqualTo(5);
        assertThat(cols.minY()).isEqualTo(70);
        assertThat(cols.maxY()).isEqualTo(70);
        assertThat(cols.minZ()).isEqualTo(-3);
        assertThat(cols.maxZ()).isEqualTo(-3);
    }

    @Test
    void cellIndexSurvivesGrowthAndExtremeCoordinates() {
        BlockColumns cols = new BlockColumns(2);
        int id = cols.intern("minecraft:stone");
        int other = cols.intern("minecraft:dirt");
        // Push far past the initial table so the index rehashes, with
        // world-border-scale and negative coordinates in the mix.
        for (int i = 0; i < 500; i++) {
            assertThat(cols.addOrReplace(29_999_000 - i, -64 + (i % 300), -29_999_000 + i, id, -1))
                    .isTrue();
        }
        assertThat(cols.count()).isEqualTo(500);
        // Every one of the 500 still folds instead of appending.
        for (int i = 0; i < 500; i++) {
            assertThat(cols.addOrReplace(29_999_000 - i, -64 + (i % 300), -29_999_000 + i, other, -1))
                    .isFalse();
        }
        assertThat(cols.count()).isEqualTo(500);
        assertThat(cols.replData(0)).isEqualTo("minecraft:dirt");
        assertThat(cols.replData(499)).isEqualTo("minecraft:dirt");
    }

    @Test
    void nearMissNeighborsAreNotFolded() {
        // Cells that differ in exactly one axis by one must never share
        // a packed key (the classic packing-collision bug shape).
        BlockColumns cols = new BlockColumns(8);
        int id = cols.intern("minecraft:stone");
        assertThat(cols.addOrReplace(0, 0, 0, id, -1)).isTrue();
        assertThat(cols.addOrReplace(1, 0, 0, id, -1)).isTrue();
        assertThat(cols.addOrReplace(-1, 0, 0, id, -1)).isTrue();
        assertThat(cols.addOrReplace(0, 1, 0, id, -1)).isTrue();
        assertThat(cols.addOrReplace(0, -1, 0, id, -1)).isTrue();
        assertThat(cols.addOrReplace(0, 0, 1, id, -1)).isTrue();
        assertThat(cols.addOrReplace(0, 0, -1, id, -1)).isTrue();
        assertThat(cols.count()).isEqualTo(7);
    }
}
