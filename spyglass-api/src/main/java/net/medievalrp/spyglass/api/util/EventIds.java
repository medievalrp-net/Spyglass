package net.medievalrp.spyglass.api.util;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Time-ordered (version 7) UUIDs for
 * {@link net.medievalrp.spyglass.api.event.EventRecord} ids.
 *
 * <p>Record ids exist for uniqueness and keyset tie-breaking, not
 * security. UUIDv7 keeps both properties and adds a 48-bit
 * millisecond-timestamp prefix that ids minted close in time share —
 * which makes the id column compress roughly 2x in columnar stores,
 * where fully random v4 bytes are incompressible by definition.
 * Extensions creating their own records should mint ids here for the
 * same reason.
 */
public final class EventIds {

    private EventIds() {
    }

    public static UUID newId() {
        long now = System.currentTimeMillis();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Layout per RFC 9562: unix_ts_ms(48) | version(4)=7 | rand_a(12)
        // then variant(2)=0b10 | rand_b(62).
        long msb = (now << 16) | 0x7000L | (random.nextLong() & 0x0FFFL);
        long lsb = 0x8000000000000000L | (random.nextLong() & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(msb, lsb);
    }
}
