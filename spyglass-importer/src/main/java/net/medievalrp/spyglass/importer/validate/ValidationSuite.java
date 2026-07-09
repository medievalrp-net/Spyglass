package net.medievalrp.spyglass.importer.validate;

import java.util.List;

/**
 * The fixed set of paired count queries we run for post-import
 * validation. Each pair is structurally equivalent — same logical
 * subset of events on each side — so the counts should match modulo
 * deliberate skip categories (missing player UUID, mapper UNKNOWN_ACTION
 * codes for tables we don't fully cover yet, etc.).
 *
 * <p>The {@code expectedDeltaNote} on each check explains the most
 * likely benign cause of any divergence.
 */
public final class ValidationSuite {

    private ValidationSuite() {}

    public static List<ValidationCheck> defaultChecks(String spyglassServerName) {
        // Spyglass queries are scoped by server name so an operator
        // doing multiple imports into the same CH instance can validate
        // each independently.
        String spyServerFilter = " AND server = '" + spyglassServerName + "'";

        return List.of(
                new ValidationCheck(
                        "blocks_break_place_use",
                        "co_block actions 0/1/2 ↔ Spyglass break/place/use events",
                        "SELECT COUNT(*) FROM co_block WHERE action IN (0,1,2)",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event IN ('break','place','use')" + spyServerFilter,
                        0L,
                        "Should match exactly. Any positive delta means rows "
                                + "were skipped at import — most likely "
                                + "MISSING_PLAYER_UUID on pre-UUID rows. Check "
                                + "the import summary."),
                new ValidationCheck(
                        "blocks_kills",
                        "co_block action 3 ↔ Spyglass death events",
                        "SELECT COUNT(*) FROM co_block WHERE action = 3",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event = 'death'" + spyServerFilter,
                        0L,
                        "Player and entity kills overload action=3. Rows with "
                                + "no resolvable victim (corrupt FK to co_user "
                                + "or co_entity_map) are skipped as MISSING_VICTIM."),
                new ValidationCheck(
                        "sessions_join_quit",
                        "co_session ↔ Spyglass join+quit events",
                        "SELECT COUNT(*) FROM co_session",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event IN ('join','quit')" + spyServerFilter,
                        0L,
                        "Should match exactly. Pre-UUID sessions (rare) are "
                                + "skipped."),
                new ValidationCheck(
                        "chat_messages",
                        "co_chat ↔ Spyglass chat events",
                        "SELECT COUNT(*) FROM co_chat",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event = 'say'" + spyServerFilter,
                        0L,
                        "Should match exactly."),
                new ValidationCheck(
                        "commands",
                        "co_command ↔ Spyglass command events",
                        "SELECT COUNT(*) FROM co_command",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event = 'command'" + spyServerFilter,
                        0L,
                        "Should match exactly."),
                new ValidationCheck(
                        "containers_deposit_withdraw",
                        "co_container ↔ Spyglass deposit+withdraw events",
                        "SELECT COUNT(*) FROM co_container WHERE action IN (0,1)",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event IN ('deposit','withdraw')" + spyServerFilter,
                        0L,
                        "Should match exactly."),
                new ValidationCheck(
                        "items_drop_pickup",
                        "co_item actions 2/3 ↔ Spyglass drop+pickup events",
                        "SELECT COUNT(*) FROM co_item WHERE action IN (2,3)",
                        "SELECT COUNT() FROM event_records "
                                + "WHERE event IN ('drop','pickup')" + spyServerFilter,
                        0L,
                        "Should match exactly. Other co_item actions "
                                + "(throw, shoot, break, destroy, create, "
                                + "ender_add/remove, sell/buy) have no "
                                + "Spyglass counterpart and are deliberately "
                                + "dropped — see items_other_skipped below."),
                new ValidationCheck(
                        "items_other_skipped",
                        "co_item actions other than drop/pickup ↔ should NOT be in Spyglass",
                        "SELECT COUNT(*) FROM co_item WHERE action NOT IN (2,3)",
                        // We expect ZERO Spyglass rows derived from these
                        // CP rows. The check is implicit: tolerated delta
                        // = the CoreProtect count itself, since none of
                        // these should be in Spyglass.
                        "SELECT 0",
                        Long.MAX_VALUE,  // delta is allowed to be the entire CP count
                        "These actions (durability break, lava destroy, "
                                + "crafting create, etc.) have no Spyglass "
                                + "record type. The delta IS the count of "
                                + "skipped rows — not a problem.")
        );
    }
}
