package net.medievalrp.spyglass.importer.mapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Generates deterministic UUIDs for imported CoreProtect rows so re-runs
 * of the importer collapse via the {@code event_records} ReplacingMergeTree
 * instead of creating duplicates. The recipe is a v3 (MD5) UUID with a
 * fixed namespace plus the qualified row identity
 * ({@code "coreprotect:<table>:<rowid>"}).
 *
 * <p>Stable across importer runs, sources (sqlite vs mysql), and JVMs.
 */
public final class DeterministicId {

    // Random fixed UUID — chosen once, never changes. Treat as opaque.
    private static final byte[] NAMESPACE_BYTES = uuidToBytes(
            UUID.fromString("3a4f9c2e-7b1d-4e8a-9f12-c0a81e5b6f44"));

    private DeterministicId() {}

    public static UUID forRow(String coreProtectTable, long rowid) {
        String name = "coreprotect:" + coreProtectTable + ":" + rowid;
        return v3(NAMESPACE_BYTES, name.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID v3(byte[] namespace, byte[] name) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 unavailable on this JVM", ex);
        }
        md.update(namespace);
        md.update(name);
        byte[] hash = md.digest();
        // Stamp version (3) and variant (RFC 4122) bits per RFC 4122 §4.3.
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x30);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        long msb = 0L, lsb = 0L;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (hash[i] & 0xffL);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xffL);
        return new UUID(msb, lsb);
    }

    private static byte[] uuidToBytes(UUID u) {
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) out[i] = (byte) (msb >>> (8 * (7 - i)));
        for (int i = 0; i < 8; i++) out[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        return out;
    }
}
