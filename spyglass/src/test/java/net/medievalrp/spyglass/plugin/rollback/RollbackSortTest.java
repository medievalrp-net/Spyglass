package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RollbackEngine#sortParallelByChunk} — the primitive
 * packed-long fast path must produce byte-for-byte the same ordering as
 * the comparator it replaced. That ordering (chunk grouping + bottom-up
 * Y, tie-broken by original index) is gravity-critical: restoring a
 * gravel block before its support turns it into a falling entity, so any
 * drift here is a correctness bug, not just a perf one.
 */
class RollbackSortTest {

    private static final UUID WORLD_A = new UUID(0xAAAAL, 0x1111L);
    private static final UUID WORLD_B = new UUID(0xBBBBL, 0x2222L);

    // Independent oracle: the exact ordering the old comparator produced.
    private static final Comparator<RollbackEffect.BlockReplace> ORACLE =
            (ea, eb) -> {
                BlockLocation la = ea.location();
                BlockLocation lb = eb.location();
                int c = la.worldId().compareTo(lb.worldId());
                if (c != 0) return c;
                c = Integer.compare(la.x() >> 4, lb.x() >> 4);
                if (c != 0) return c;
                c = Integer.compare(la.z() >> 4, lb.z() >> 4);
                if (c != 0) return c;
                return Integer.compare(la.y(), lb.y());
            };

    @Test
    void fastPathMatchesComparator_singleWorldNegativeCoords() {
        assertSortMatchesOracle(buildEffects(6000, 1, /*spanBlocks*/256, 7L));
    }

    @Test
    void fastPathMatchesComparator_singleColumnManyY() {
        // span 0 in cx/cz -> cxBits/czBits collapse to 0; still must order
        // by Y then original index.
        List<RollbackEffect.BlockReplace> effects = new ArrayList<>();
        Random rnd = new Random(99L);
        for (int i = 0; i < 1000; i++) {
            int y = rnd.nextInt(384) - 64;
            effects.add(replace(WORLD_A, 5, y, 9)); // same x,z -> same chunk+column
        }
        assertSortMatchesOracle(effects);
    }

    @Test
    void fallbackMatchesComparator_multiWorld() {
        // Two worlds -> fast path bails to the comparator fallback; order
        // must still match the oracle (worldId first).
        List<RollbackEffect.BlockReplace> effects = new ArrayList<>();
        Random rnd = new Random(123L);
        for (int i = 0; i < 4000; i++) {
            UUID w = rnd.nextBoolean() ? WORLD_A : WORLD_B;
            effects.add(replace(w, rnd.nextInt(256) - 128, rnd.nextInt(320) - 64, rnd.nextInt(256) - 128));
        }
        assertSortMatchesOracle(effects);
    }

    @Test
    void edgeCases_emptyAndSingle() {
        assertSortMatchesOracle(new ArrayList<>());
        assertSortMatchesOracle(List.of(replace(WORLD_A, 1, 2, 3)));
    }

    private static List<RollbackEffect.BlockReplace> buildEffects(int n, int worlds, int spanBlocks, long seed) {
        Random rnd = new Random(seed);
        List<RollbackEffect.BlockReplace> effects = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID w = worlds == 1 ? WORLD_A : (rnd.nextBoolean() ? WORLD_A : WORLD_B);
            int x = rnd.nextInt(spanBlocks) - spanBlocks / 2;
            int z = rnd.nextInt(spanBlocks) - spanBlocks / 2;
            int y = rnd.nextInt(384) - 64;
            effects.add(replace(w, x, y, z));
        }
        return effects;
    }

    // Runs the production sort and asserts both the carried index payload
    // and the effect order match a stable oracle sort.
    private static void assertSortMatchesOracle(List<RollbackEffect.BlockReplace> source) {
        int n = source.size();
        // Carry an arbitrary payload in `indices` to prove it moves in lockstep.
        List<Integer> indices = new ArrayList<>(n);
        List<RollbackEffect.BlockReplace> effects = new ArrayList<>(source);
        for (int i = 0; i < n; i++) indices.add(1000 + i);

        // Oracle: stable sort of positions; List.sort / Arrays.sort on
        // boxed Integer is stable, so ties keep original-index order —
        // exactly the production tie-break.
        Integer[] pos = new Integer[n];
        for (int i = 0; i < n; i++) pos[i] = i;
        Arrays.sort(pos, (a, b) -> ORACLE.compare(effects.get(a), effects.get(b)));
        List<Integer> expectedIndices = new ArrayList<>(n);
        List<RollbackEffect.BlockReplace> expectedEffects = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            expectedIndices.add(indices.get(pos[k]));
            expectedEffects.add(effects.get(pos[k]));
        }

        RollbackEngine.sortParallelByChunk(indices, effects);

        assertThat(indices).as("carried index payload order").isEqualTo(expectedIndices);
        for (int k = 0; k < n; k++) {
            assertThat(effects.get(k)).as("effect at %d", k).isSameAs(expectedEffects.get(k));
        }
    }

    private static RollbackEffect.BlockReplace replace(UUID world, int x, int y, int z) {
        BlockLocation loc = new BlockLocation(world, "world", x, y, z);
        return new RollbackEffect.BlockReplace(loc, snapshot(), snapshot());
    }

    private static BlockSnapshot snapshot() {
        return new BlockSnapshot(Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
    }
}
