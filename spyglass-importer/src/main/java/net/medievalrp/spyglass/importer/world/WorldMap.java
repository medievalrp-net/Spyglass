package net.medievalrp.spyglass.importer.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves CoreProtect world names ({@code "world"}, {@code "world_nether"})
 * to the Bukkit world UUIDs Spyglass keys on. Built once at startup from
 * a worlds directory; missing worlds fail validation before any rows are
 * written.
 */
public final class WorldMap {

    private final Map<String, UUID> nameToUuid;

    private WorldMap(Map<String, UUID> nameToUuid) {
        this.nameToUuid = Map.copyOf(nameToUuid);
    }

    public UUID get(String worldName) {
        return nameToUuid.get(worldName);
    }

    public boolean contains(String worldName) {
        return nameToUuid.containsKey(worldName);
    }

    public Map<String, UUID> asMap() {
        return nameToUuid;
    }

    /**
     * Resolve every name in {@code requiredNames} against {@code worldsDir}.
     * Throws if any are missing or unreadable; returns the mapping
     * otherwise. Resolution is "directory under worldsDir with the same
     * name, containing a {@code uid.dat}".
     */
    public static WorldMap resolve(Path worldsDir, List<String> requiredNames) throws IOException {
        if (!Files.isDirectory(worldsDir)) {
            throw new IOException("--worlds-dir " + worldsDir + " is not a directory");
        }
        Map<String, UUID> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (String name : requiredNames) {
            Path uidDat = worldsDir.resolve(name).resolve("uid.dat");
            if (!Files.isRegularFile(uidDat)) {
                missing.add(name + " (expected " + uidDat + ")");
                continue;
            }
            try {
                resolved.put(name, UidDatReader.read(uidDat));
            } catch (IOException ex) {
                unreadable.add(name + ": " + ex.getMessage());
            }
        }
        if (!missing.isEmpty() || !unreadable.isEmpty()) {
            StringBuilder msg = new StringBuilder("Cannot resolve world UUIDs from ")
                    .append(worldsDir).append(":");
            if (!missing.isEmpty()) {
                msg.append("\n  Missing uid.dat:\n    - ")
                        .append(String.join("\n    - ", missing));
            }
            if (!unreadable.isEmpty()) {
                msg.append("\n  Unreadable uid.dat:\n    - ")
                        .append(String.join("\n    - ", unreadable));
            }
            throw new IOException(msg.toString());
        }
        return new WorldMap(resolved);
    }
}
