package net.medievalrp.spyglass.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-session cascade dedup key (#209).
 *
 * <p>The old packing {@code (y << 56) | ((x & 0xFFFFFFF) << 28) | (z & 0xFFFFFFF)}
 * left y only 8 bits (unmasked), so two cells 256 apart at the same (x, z)
 * collided and a negative y sign-extended into the x field - both silently
 * dropping cascade break records. These tests assert the new {@link
 * FallingBlockCascade#packCell} is collision-free across a full world column,
 * including negative y, and that the exact failure the old formula exhibited no
 * longer reproduces.
 */
class FallingBlockCascadeTest {

    @Test
    void distinctCellsInAWorldColumnNeverCollide() {
        // A single (x, z) column across the full modern overworld height
        // (-64..319) plus a couple of extreme customs. Every y must map to a
        // distinct key, or a cascade above a broken support would be deduped
        // away and never recorded.
        Set<Long> keys = new HashSet<>();
        int x = 12_345;
        int z = -6_789;
        for (int y = -64; y <= 2032; y++) {
            assertThat(keys.add(FallingBlockCascade.packCell(x, y, z)))
                    .as("y=%d collided with an earlier y in the same column", y)
                    .isTrue();
        }
    }

    @Test
    void cellsExactly256ApartDoNotCollide() {
        // The reported failure: y<<56 kept only 8 bits, so y and y+256 shared a
        // key. Both directions, including a negative lower cell.
        assertThat(FallingBlockCascade.packCell(10, 64, 20))
                .isNotEqualTo(FallingBlockCascade.packCell(10, 320, 20));
        assertThat(FallingBlockCascade.packCell(-3, -64, 7))
                .isNotEqualTo(FallingBlockCascade.packCell(-3, 192, 7));
    }

    @Test
    void negativeYDoesNotCorruptTheXOrZField() {
        // A negative y must not bleed into the x/z bits: two different columns
        // at the same negative y stay distinct, and a negative-y cell differs
        // from the same column at a positive y.
        assertThat(FallingBlockCascade.packCell(100, -64, 200))
                .isNotEqualTo(FallingBlockCascade.packCell(101, -64, 200));
        assertThat(FallingBlockCascade.packCell(100, -64, 200))
                .isNotEqualTo(FallingBlockCascade.packCell(100, -64, 201));
        assertThat(FallingBlockCascade.packCell(100, -64, 200))
                .isNotEqualTo(FallingBlockCascade.packCell(100, 64, 200));
    }

    @Test
    void oldFormulaCollidedWhereTheNewOneDoesNot() {
        // Documents the bug: the old key formula collapses (10,64,20) and
        // (10,320,20) to one value; the fix separates them.
        long oldLower = ((long) 64 << 56) | ((long) (10 & 0xFFFFFFF) << 28) | (20 & 0xFFFFFFF);
        long oldUpper = ((long) 320 << 56) | ((long) (10 & 0xFFFFFFF) << 28) | (20 & 0xFFFFFFF);
        assertThat(oldLower).as("old formula collided (regression guard)").isEqualTo(oldUpper);

        assertThat(FallingBlockCascade.packCell(10, 64, 20))
                .isNotEqualTo(FallingBlockCascade.packCell(10, 320, 20));
    }
}
