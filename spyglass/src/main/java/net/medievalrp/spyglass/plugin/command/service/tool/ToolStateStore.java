package net.medievalrp.spyglass.plugin.command.service.tool;

import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-player wand-enabled state.
 *
 * <p>Two implementations: {@link MongoToolStateStore} for Mongo
 * deployments and {@link ClickHouseToolStateStore} for ClickHouse
 * deployments. The plugin picks one at startup based on
 * {@code database.backend}.
 */
@ApiStatus.Internal
public interface ToolStateStore {

    Collection<UUID> loadActive();

    void enable(UUID playerId);

    void disable(UUID playerId);
}
