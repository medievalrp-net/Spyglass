package net.medievalrp.spyglass.api.util;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sequence-embedded record ids for
 * {@link net.medievalrp.spyglass.api.event.EventRecord}s.
 *
 * <p>Record ids exist for uniqueness and keyset tie-breaking, not
 * security. The id is a 62-bit snowflake-style sequence —
 * {@code ms-since-2024(40+) | instance(8) | counter(14)} — embedded in
 * an RFC 9562 version-8 UUID so the public API keeps its {@code UUID}
 * contract. Successive ids differ by tiny deltas, which is what lets
 * the columnar id column delta-compress to ~1-2 bytes per row where
 * random UUID bytes are incompressible by definition (storage v2,
 * issue #44). Extensions creating their own records should mint ids
 * here for the same reason.
 *
 * <p>The 8 instance bits separate id streams when multiple backend
 * servers share one store; {@link #bindInstance} is called at plugin
 * bootstrap with a hash of {@code server.name}. The 14-bit counter
 * allows 16 384 ids per millisecond per instance; on overflow the
 * mint spins to the next millisecond.
 */
public final class EventIds {

    /** 2024-01-01T00:00:00Z — keeps the ms field small for ~70 years. */
    private static final long CUSTOM_EPOCH_MS = 1_704_067_200_000L;
    /** Constant msb: "SG" brand bytes + the UUID version-8 nibble. */
    private static final long MSB_MARKER = 0x5347000000008000L;
    private static final long VARIANT_BIT = 0x8000000000000000L;
    private static final long U64_MASK = 0x3FFFFFFFFFFFFFFFL;
    private static final int INSTANCE_BITS = 8;
    private static final int SEQ_BITS = 14;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;

    // Packed mint state: msSinceEpoch << SEQ_BITS | seq. CAS-advanced.
    private static final AtomicLong STATE = new AtomicLong();
    // Defaults to a random byte so headless tests and misconfigured
    // bootstraps still mint unique-enough streams.
    private static volatile long instance =
            ThreadLocalRandom.current().nextInt(1 << INSTANCE_BITS);

    private EventIds() {
    }

    /**
     * Bind the per-server id stream. Call once at bootstrap, before
     * listeners come online, with a stable per-server value (Spyglass
     * passes a hash of {@code server.name}).
     */
    public static void bindInstance(int value) {
        instance = value & ((1 << INSTANCE_BITS) - 1);
    }

    public static UUID newId() {
        return uuidOf(nextSequence());
    }

    /**
     * The raw 62-bit sequence inside an id minted by {@link #newId}.
     * Foreign UUIDs (not minted here) fold deterministically into the
     * same domain so storage backends can key them; the fold is not
     * reversible, which only affects records whose ids bypassed this
     * class.
     */
    public static long sequenceOf(UUID id) {
        if (id.getMostSignificantBits() == MSB_MARKER) {
            return id.getLeastSignificantBits() & U64_MASK;
        }
        return (id.getLeastSignificantBits() ^ id.getMostSignificantBits()) & U64_MASK;
    }

    /** The canonical UUID for a raw sequence (inverse of {@link #sequenceOf}). */
    public static UUID uuidOf(long sequence) {
        return new UUID(MSB_MARKER, VARIANT_BIT | (sequence & U64_MASK));
    }

    private static long nextSequence() {
        while (true) {
            long now = System.currentTimeMillis() - CUSTOM_EPOCH_MS;
            long state = STATE.get();
            long stateMs = state >>> SEQ_BITS;
            long candidate;
            if (now > stateMs) {
                candidate = now << SEQ_BITS;
            } else {
                // Same (or rewound) millisecond: bump the counter; on
                // overflow spin into the next millisecond so ids stay
                // strictly increasing per instance.
                long seq = (state & SEQ_MASK) + 1;
                candidate = seq > SEQ_MASK
                        ? (stateMs + 1) << SEQ_BITS
                        : (stateMs << SEQ_BITS) | seq;
            }
            if (STATE.compareAndSet(state, candidate)) {
                long ms = candidate >>> SEQ_BITS;
                long seq = candidate & SEQ_MASK;
                return (ms << (INSTANCE_BITS + SEQ_BITS))
                        | (instance << SEQ_BITS)
                        | seq;
            }
        }
    }
}
