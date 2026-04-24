package net.medievalrp.omniscience2.api.event;

import java.util.UUID;
import net.medievalrp.omniscience2.api.util.BlockLocation;

public record Source(
        String kind,
        UUID playerId,
        String playerName,
        UUID entityId,
        String entityType,
        String pluginName,
        BlockLocation commandBlockLocation,
        String description) {

    public static final String PLAYER = "player";
    public static final String ENTITY = "entity";
    public static final String PLUGIN = "plugin";
    public static final String CONSOLE = "console";
    public static final String COMMAND_BLOCK = "command-block";
    public static final String ENVIRONMENT = "environment";

    public static Source player(UUID id, String name) {
        return new Source(PLAYER, id, name, null, null, null, null, null);
    }

    public static Source entity(UUID id, String type) {
        return new Source(ENTITY, null, null, id, type, null, null, null);
    }

    public static Source plugin(String pluginName) {
        return new Source(PLUGIN, null, null, null, null, pluginName, null, null);
    }

    public static Source console() {
        return new Source(CONSOLE, null, null, null, null, null, null, null);
    }

    public static Source commandBlock(BlockLocation location) {
        return new Source(COMMAND_BLOCK, null, null, null, null, null, location, null);
    }

    public static Source environment(String description) {
        return new Source(ENVIRONMENT, null, null, null, null, null, null, description);
    }

    public String displayName() {
        return switch (kind == null ? "" : kind) {
            case PLAYER -> playerName == null ? "unknown-player" : playerName;
            case ENTITY -> entityType == null ? "unknown-entity" : entityType;
            case PLUGIN -> pluginName == null ? "plugin" : pluginName;
            case CONSOLE -> "console";
            case COMMAND_BLOCK -> "command_block";
            case ENVIRONMENT -> description == null ? "environment" : description;
            default -> kind == null ? "unknown" : kind;
        };
    }
}
