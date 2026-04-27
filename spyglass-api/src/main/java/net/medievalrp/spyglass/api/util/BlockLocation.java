package net.medievalrp.spyglass.api.util;

import java.util.UUID;

public record BlockLocation(UUID worldId, String worldName, int x, int y, int z) {
}
