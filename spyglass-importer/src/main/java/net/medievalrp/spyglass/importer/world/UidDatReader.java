package net.medievalrp.spyglass.importer.world;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Reads a Bukkit world UUID from {@code <world>/uid.dat}. The file is
 * exactly 16 bytes: most-significant long, then least-significant long,
 * big-endian. Bukkit writes it the first time the world loads and never
 * touches it again.
 */
public final class UidDatReader {

    private UidDatReader() {}

    public static UUID read(Path uidDat) throws IOException {
        long size = Files.size(uidDat);
        if (size != 16L) {
            throw new IOException("uid.dat at " + uidDat
                    + " is " + size + " bytes; expected exactly 16");
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(uidDat))) {
            long msb = in.readLong();
            long lsb = in.readLong();
            return new UUID(msb, lsb);
        }
    }
}
