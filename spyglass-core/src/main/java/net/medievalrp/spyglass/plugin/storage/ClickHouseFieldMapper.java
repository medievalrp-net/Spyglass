package net.medievalrp.spyglass.plugin.storage;

import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * Maps the dotted Mongo-style field paths that {@link
 * net.medievalrp.spyglass.api.query.QueryPredicate} uses onto the
 * flat {@link ClickHouseSchema} column names.
 *
 * <p>Only the indexed / filterable scalar paths are mapped here. Deep
 * paths into the nested snapshot blobs ({@code item.name},
 * {@code beforeItem.lore}, {@code originalBlock.containerItems.lore},
 * etc.) cannot be translated — the snapshot fields live as opaque
 * BSON byte strings on the CH side and the server has no way to
 * search inside them. Those queries are rejected at translate time
 * (see {@link PredicateToSql.UnsupportedPredicateException}); the
 * caller can fall back to Mongo or warn the user that the search
 * is unsupported on the ClickHouse backend.
 */
@ApiStatus.Internal
final class ClickHouseFieldMapper {

    private static final Map<String, String> COLUMN = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("event", "event"),
            Map.entry("occurred", "occurred"),
            Map.entry("expiresAt", "expires_at"),
            Map.entry("target", "target"),
            Map.entry("message", "message"),
            Map.entry("origin.kind", "origin_kind"),
            Map.entry("origin.detail", "origin_detail"),
            Map.entry("source.kind", "source_kind"),
            Map.entry("source.playerId", "source_player_id"),
            Map.entry("source.playerName", "source_player_name"),
            Map.entry("source.entityId", "source_entity_id"),
            Map.entry("source.entityType", "source_entity_type"),
            Map.entry("source.pluginName", "source_plugin_name"),
            Map.entry("source.description", "source_description"),
            Map.entry("location.worldId", "location_world_id"),
            Map.entry("location.worldName", "location_world_name"),
            Map.entry("location.x", "location_x"),
            Map.entry("location.y", "location_y"),
            Map.entry("location.z", "location_z"),
            Map.entry("server", "server"));

    private ClickHouseFieldMapper() {
    }

    static String columnFor(String fieldPath) {
        return COLUMN.get(fieldPath);
    }
}
