package net.medievalrp.spyglass.importer.bench;

import java.util.List;

/**
 * The fixed set of paired queries we time. Each is something an
 * operator would actually ask: "did anyone break diamond ore at this
 * coord?", "what did this player do in the last day?", "show me all
 * chest deposits of an item type", etc.
 *
 * <p>Where possible the SQL on both sides returns a {@code COUNT(*)} so
 * we measure the storage-engine cost, not row-materialization cost.
 * For ranked / sorted queries (e.g. "20 most recent X by player Y") we
 * have to materialize rows; both sides apply the same {@code LIMIT}.
 */
public final class BenchSuite {

    private BenchSuite() {}

    public static List<QueryCase> defaultCases() {
        return List.of(
                new QueryCase(
                        "count_breaks_total",
                        "Total break events across the dataset.",
                        "SELECT COUNT(*) FROM co_block WHERE action = 0 AND rolled_back = 0",
                        "SELECT COUNT() FROM event_records WHERE event = 'break'"),
                new QueryCase(
                        "count_places_total",
                        "Total place events across the dataset.",
                        "SELECT COUNT(*) FROM co_block WHERE action = 1 AND rolled_back = 0",
                        "SELECT COUNT() FROM event_records WHERE event = 'place'"),
                new QueryCase(
                        "count_breaks_by_top_player",
                        "Break events attributed to the dataset's top breaker.",
                        // CoreProtect: join co_user; pick the player with the most breaks.
                        // The SQL is a 2-stage query expressed as a CTE-ish form.
                        "SELECT COUNT(*) FROM co_block "
                                + "WHERE action = 0 AND user = ("
                                + "  SELECT user FROM co_block WHERE action = 0 "
                                + "  GROUP BY user ORDER BY COUNT(*) DESC LIMIT 1)",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event = 'break' AND source_player_id = ("
                                + "  SELECT source_player_id FROM event_records "
                                + "  WHERE event = 'break' AND source_player_id IS NOT NULL "
                                + "  GROUP BY source_player_id ORDER BY COUNT() DESC LIMIT 1)"),
                new QueryCase(
                        "count_diamond_ore_breaks",
                        "All diamond_ore breaks in the dataset.",
                        "SELECT COUNT(*) FROM co_block cb "
                                + "JOIN co_material_map cm ON cm.id = cb.type "
                                + "WHERE cb.action = 0 AND cm.material = 'minecraft:diamond_ore'",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event = 'break' AND target = 'DIAMOND_ORE'"),
                new QueryCase(
                        "count_breaks_in_radius_30",
                        "Breaks within a 30-block radius of a hot point in the data.",
                        // (x=30100, y=52, z=5368) is one observed coord;
                        // pick something from the dataset's centre-of-mass.
                        "SELECT COUNT(*) FROM co_block "
                                + "WHERE action = 0 "
                                + "  AND x BETWEEN 30070 AND 30130 "
                                + "  AND z BETWEEN 5338 AND 5398",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event = 'break' "
                                + "  AND location_x BETWEEN 30070 AND 30130 "
                                + "  AND location_z BETWEEN 5338 AND 5398"),
                new QueryCase(
                        "count_chat_total",
                        "All chat messages — exercises a wholly different table.",
                        "SELECT COUNT(*) FROM co_chat",
                        "SELECT COUNT() FROM event_records WHERE event = 'say'"),
                new QueryCase(
                        "count_commands_with_substring",
                        "Commands containing the substring 'gun' (xc browse gun et al).",
                        "SELECT COUNT(*) FROM co_command WHERE message LIKE '%gun%'",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event = 'command' AND positionUTF8(command_line, 'gun') > 0"),
                new QueryCase(
                        "count_deaths_total",
                        "All death events (CoreProtect action=3 in co_block).",
                        "SELECT COUNT(*) FROM co_block WHERE action = 3",
                        "SELECT COUNT() FROM event_records WHERE event = 'death'"),
                new QueryCase(
                        "page_recent_20_breaks",
                        "20 most recent breaks across the dataset (rendered).",
                        "SELECT cb.time, cb.x, cb.y, cb.z, cu.user, cm.material "
                                + "FROM co_block cb "
                                + "JOIN co_user cu ON cu.rowid = cb.user "
                                + "JOIN co_material_map cm ON cm.id = cb.type "
                                + "WHERE cb.action = 0 "
                                + "ORDER BY cb.time DESC LIMIT 20",
                        "SELECT occurred, location_x, location_y, location_z, "
                                + "       source_player_name, target "
                                + "FROM event_records "
                                + "WHERE event = 'break' "
                                + "ORDER BY occurred DESC LIMIT 20")
        );
    }
}
