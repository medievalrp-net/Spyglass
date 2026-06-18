package net.medievalrp.spyglass.plugin.rollback;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Primitive, columnar representation of one world's <em>simple</em>
 * block-replace effects within a rollback window (#67).
 *
 * <p>The object path represents a window as a {@code List} of
 * {@link net.medievalrp.spyglass.api.rollback.RollbackEffect.BlockReplace},
 * each holding two {@link net.medievalrp.spyglass.api.event.BlockSnapshot}
 * objects, and produces a {@code RollbackResult} object per cell. On a
 * 2M-block rollback that graph lives across the multi-tick apply and, under
 * Aikar's {@code MaxTenuringThreshold=1}, promotes wholesale to old gen —
 * the allocation that forces the Mixed-GC freeze.
 *
 * <p>This holds the same information as parallel {@code int} arrays plus a
 * small interned block-data palette: a cell is its x/y/z, the palette id of
 * the block-data to write, and the palette id of the expected-current
 * block-data ({@code -1} = no expected check). The footprint is
 * reference-free primitives that cost almost nothing to evacuate and never
 * drag a graph into old gen, so a window can survive a young GC without
 * provoking a Mixed collection. Only blocks with no tile-entity payload
 * (the overwhelming majority of a bulk rollback) live here; containers,
 * signs, banners, entities, and non-simple blocks stay on the object path.
 *
 * <p>Not thread-safe: filled by the single reader thread, then read — after
 * the queue handoff establishes happens-before — by the apply threads.
 */
@ApiStatus.Internal
public final class BlockColumns {

    private int[] xs;
    private int[] ys;
    private int[] zs;
    private int[] replId;
    private int[] expId;
    private int count;

    // Tiny per-window palette: a bulk rollback repeats a handful of
    // materials, so each cell carries an int id into this shared table
    // instead of its own block-data string reference.
    private String[] palette = new String[16];
    private int paletteCount;
    private final Map<String, Integer> paletteIndex = new HashMap<>();

    // Running bounds for the physics-suppression box and the operation
    // reference, accumulated on add() so neither needs a second pass.
    private int minX = Integer.MAX_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int maxY = Integer.MIN_VALUE;
    private int maxZ = Integer.MIN_VALUE;

    public BlockColumns(int initialCapacity) {
        int cap = Math.max(16, initialCapacity);
        xs = new int[cap];
        ys = new int[cap];
        zs = new int[cap];
        replId = new int[cap];
        expId = new int[cap];
    }

    /** Intern a block-data string to its palette id; {@code -1} for null. */
    public int intern(@Nullable String blockData) {
        if (blockData == null) {
            return -1;
        }
        Integer existing = paletteIndex.get(blockData);
        if (existing != null) {
            return existing;
        }
        if (paletteCount == palette.length) {
            palette = Arrays.copyOf(palette, palette.length * 2);
        }
        int id = paletteCount;
        palette[paletteCount++] = blockData;
        paletteIndex.put(blockData, id);
        return id;
    }

    /** Append one cell. {@code expPaletteId} of {@code -1} = no expected check. */
    public void add(int x, int y, int z, int replPaletteId, int expPaletteId) {
        if (count == xs.length) {
            int n = xs.length * 2;
            xs = Arrays.copyOf(xs, n);
            ys = Arrays.copyOf(ys, n);
            zs = Arrays.copyOf(zs, n);
            replId = Arrays.copyOf(replId, n);
            expId = Arrays.copyOf(expId, n);
        }
        xs[count] = x;
        ys[count] = y;
        zs[count] = z;
        replId[count] = replPaletteId;
        expId[count] = expPaletteId;
        count++;
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (z < minZ) minZ = z;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
        if (z > maxZ) maxZ = z;
    }

    public int count() {
        return count;
    }

    /** Distinct block-data strings interned so far (the dedup factor). */
    public int paletteSize() {
        return paletteCount;
    }

    public int x(int i) {
        return xs[i];
    }

    public int y(int i) {
        return ys[i];
    }

    public int z(int i) {
        return zs[i];
    }

    /** Block-data string to write at cell {@code i}. */
    public String replData(int i) {
        return palette[replId[i]];
    }

    /** Expected-current block-data at cell {@code i}, or null for no check. */
    @Nullable
    public String expData(int i) {
        int id = expId[i];
        return id < 0 ? null : palette[id];
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    /**
     * Permutation of {@code [0, count)} ordered by chunk (cx, then cz) then
     * ascending Y — identical ordering to the object path's comparator.
     * Bottom-up Y matters: restoring a support block before the gravel above
     * it stops the gravel re-spawning as a falling entity. Packs
     * (relCx, relCz, y, index) into a long and sorts primitives — no boxing
     * on the hot path; falls back to a boxed comparator only when the
     * coordinate span is too wide to pack (multi-region rollbacks).
     */
    public int[] chunkSortedOrder() {
        int n = count;
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        if (n <= 1) {
            return order;
        }
        int loCx = Integer.MAX_VALUE;
        int hiCx = Integer.MIN_VALUE;
        int loCz = Integer.MAX_VALUE;
        int hiCz = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int cx = xs[i] >> 4;
            int cz = zs[i] >> 4;
            if (cx < loCx) loCx = cx;
            if (cx > hiCx) hiCx = cx;
            if (cz < loCz) loCz = cz;
            if (cz > hiCz) hiCz = cz;
        }
        int cxBits = bitsFor((long) hiCx - loCx);
        int czBits = bitsFor((long) hiCz - loCz);
        int yBits = bitsFor((long) maxY - minY);
        int idxBits = bitsFor(n - 1);
        if (cxBits + czBits + yBits + idxBits <= 62) {
            int yPos = idxBits;
            int czPos = yPos + yBits;
            int cxPos = czPos + czBits;
            long idxMask = (1L << idxBits) - 1L;
            long[] composite = new long[n];
            for (int i = 0; i < n; i++) {
                long relCx = (long) ((xs[i] >> 4) - loCx);
                long relCz = (long) ((zs[i] >> 4) - loCz);
                long relY = (long) (ys[i] - minY);
                composite[i] = (relCx << cxPos) | (relCz << czPos) | (relY << yPos) | (long) i;
            }
            Arrays.sort(composite);
            for (int i = 0; i < n; i++) {
                order[i] = (int) (composite[i] & idxMask);
            }
            return order;
        }
        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, (a, b) -> {
            int c = Integer.compare(xs[a] >> 4, xs[b] >> 4);
            if (c != 0) return c;
            c = Integer.compare(zs[a] >> 4, zs[b] >> 4);
            if (c != 0) return c;
            c = Integer.compare(ys[a], ys[b]);
            if (c != 0) return c;
            return Integer.compare(a, b);
        });
        for (int i = 0; i < n; i++) {
            order[i] = boxed[i];
        }
        return order;
    }

    private static int bitsFor(long span) {
        return span <= 0L ? 0 : 64 - Long.numberOfLeadingZeros(span);
    }
}
