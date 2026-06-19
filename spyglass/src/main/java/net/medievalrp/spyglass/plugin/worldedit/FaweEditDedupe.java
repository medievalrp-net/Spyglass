package net.medievalrp.spyglass.plugin.worldedit;

import org.jetbrains.annotations.ApiStatus;

/**
 * Per-FAWE-edit coordinate dedupe. FAWE can invoke the same batch processor
 * more than once for one edit; this keeps the first changed-cell pass and drops
 * later repeats even when chunks are processed concurrently.
 */
@ApiStatus.Internal
final class FaweEditDedupe {

    private static final int STRIPES = 64;
    private static final int INITIAL_STRIPE_CAPACITY = 64;
    private final Stripe[] stripes = new Stripe[STRIPES];

    FaweEditDedupe() {
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Stripe(INITIAL_STRIPE_CAPACITY);
        }
    }

    boolean mark(int x, int y, int z) {
        long key = blockKey(x, y, z);
        Stripe stripe = stripes[(int) mix(key) & (stripes.length - 1)];
        synchronized (stripe) {
            return stripe.add(key);
        }
    }

    int size() {
        int total = 0;
        for (Stripe stripe : stripes) {
            synchronized (stripe) {
                total += stripe.size;
            }
        }
        return total;
    }

    static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FF_FFFFL) << 38
                | ((long) z & 0x3FF_FFFFL) << 12
                | ((long) y & 0xFFFL);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    private static final class Stripe {
        private long[] keys;
        private boolean[] used;
        private int size;
        private int threshold;

        private Stripe(int capacity) {
            keys = new long[capacity];
            used = new boolean[capacity];
            threshold = capacity * 2 / 3;
        }

        private boolean add(long key) {
            if (size >= threshold) {
                grow();
            }
            int slot = findSlot(key);
            if (used[slot]) {
                return false;
            }
            used[slot] = true;
            keys[slot] = key;
            size++;
            return true;
        }

        private int findSlot(long key) {
            int mask = keys.length - 1;
            int slot = (int) mix(key) & mask;
            while (used[slot] && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            return slot;
        }

        private void grow() {
            long[] oldKeys = keys;
            boolean[] oldUsed = used;
            keys = new long[oldKeys.length * 2];
            used = new boolean[keys.length];
            threshold = keys.length * 2 / 3;
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldUsed[i]) {
                    add(oldKeys[i]);
                }
            }
        }
    }
}
