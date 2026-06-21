package net.medievalrp.spyglass.plugin.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ChunkRelighterTest {

    @Test
    void packUnpackRoundTripsAcrossSignsAndExtremes() {
        int[][] coords = {
                {0, 0}, {1, 2}, {-1, -1}, {-5, 7}, {100, -200},
                {Integer.MAX_VALUE, Integer.MIN_VALUE},
                {Integer.MIN_VALUE, Integer.MAX_VALUE},
        };
        for (int[] cxcz : coords) {
            long key = ChunkRelighter.packChunk(cxcz[0], cxcz[1]);
            assertEquals(cxcz[0], ChunkRelighter.chunkX(key),
                    "cx round-trip for " + cxcz[0] + "," + cxcz[1]);
            assertEquals(cxcz[1], ChunkRelighter.chunkZ(key),
                    "cz round-trip for " + cxcz[0] + "," + cxcz[1]);
        }
    }

    @Test
    void packingMatchesRollbackServiceScheme() {
        // RollbackService packs warmed-chunk keys as ((long)(x>>4) << 32)
        // | ((z>>4) & 0xFFFFFFFFL). The relighter consumes that set
        // directly, so the scheme must stay identical or chunks shift.
        int x = 273;
        int z = -49;
        int cx = x >> 4;
        int cz = z >> 4;
        long expected = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        assertEquals(expected, ChunkRelighter.packChunk(cx, cz));
        assertEquals(cx, ChunkRelighter.chunkX(expected));
        assertEquals(cz, ChunkRelighter.chunkZ(expected));
    }

    @Test
    void distinctChunksProduceDistinctKeys() {
        long a = ChunkRelighter.packChunk(3, 4);
        long b = ChunkRelighter.packChunk(4, 3);
        assertEquals(false, a == b, "swapped coords must not collide");
    }

    @Test
    void relightNoOpsWhenApiUnavailableOrInputEmpty() {
        // No CraftWorld/NMS on the unit-test classpath, so init() resolves
        // unavailable and every entry point degrades quietly rather than
        // throwing — the rollback must never fail because relight can't run.
        assertFalse(ChunkRelighter.isAvailable(),
                "Starlight relight must resolve unavailable without a server runtime");
        assertFalse(ChunkRelighter.relight(null, new long[]{ChunkRelighter.packChunk(0, 0)}, (cx, cz) -> { }),
                "null world must no-op");
        assertFalse(ChunkRelighter.relight(null, new long[0], (cx, cz) -> { }),
                "empty key set must no-op");
    }
}
