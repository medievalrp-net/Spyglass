package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for #263: {@code block:X} means the BLOCK. On container
 * transactions the record's {@code target} is the moved item; the container's
 * own material lives in {@code containerType}. Before the fix, {@code b:} was
 * a target-only filter - identical to {@code i:} - so {@code block:!chest}
 * still rolled back every deposit inside a chest, and {@code block:chest}
 * matched a chest ITEM inside a barrel while missing the chest's own
 * transactions. These tests pin the container-aware predicate's semantics in
 * the evaluator, which is also the SQLite/MariaDB residual path.
 */
class ContainerAwareBlockFilterTest {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID GRIEFER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    // Relative, never a wall-clock date: ClickHouse enforces expiry
    // eagerly, so a hardcoded expiry turns rows born-expired once the
    // calendar passes it (the ClickHouseSynthesisParityIT lesson).
    private static final Instant WHEN = Instant.now().minusSeconds(3600);

    /** Mirrors BlockParam.membership (#263) - the shape b:X compiles to. */
    static QueryPredicate blockShape(String name) {
        return new QueryPredicate.Or(List.of(
                new QueryPredicate.And(List.of(
                        new QueryPredicate.Exists("containerType", true),
                        new QueryPredicate.Eq("containerType", name))),
                new QueryPredicate.And(List.of(
                        new QueryPredicate.Exists("containerType", false),
                        new QueryPredicate.Eq("target", name)))));
    }

    static ContainerDepositRecord deposit(String itemTarget, String containerType, int x) {
        return new ContainerDepositRecord(
                UUID.randomUUID(), "deposit", WHEN, WHEN.plusSeconds(86400),
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new BlockLocation(WORLD, "world", x, 64, 0),
                "test", itemTarget, containerType, 3, 64,
                null, new StoredItem(3, itemTarget, "minecraft:" + itemTarget.toLowerCase()));
    }

    static BlockPlaceRecord place(Material material, int x) {
        BlockSnapshot air = new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot placed = new BlockSnapshot(material,
                "minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT),
                List.of(), List.of(), List.of(), List.of(), null);
        return new BlockPlaceRecord(UUID.randomUUID(), "place",
                WHEN, WHEN.plusSeconds(86400),
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new BlockLocation(WORLD, "world", x, 64, 0),
                "test", material.name(), air, placed);
    }

    private static boolean matches(QueryPredicate predicate, EventRecord record) {
        return PredicateEvaluator.matchesAll(List.of(predicate), record);
    }

    @Test
    void blockChestFindsTheChestsOwnTransactionsAndBlocks() {
        QueryPredicate chest = blockShape("CHEST");
        assertThat(matches(chest, deposit("DIAMOND", "CHEST", 1)))
                .as("a deposit INTO a chest is a chest event")
                .isTrue();
        assertThat(matches(chest, place(Material.CHEST, 3)))
                .as("a placed chest block is a chest event")
                .isTrue();
    }

    @Test
    void blockChestDoesNotMatchAChestItemInsideABarrel() {
        assertThat(matches(blockShape("CHEST"), deposit("CHEST", "BARREL", 2)))
                .as("the moved ITEM being a chest does not make it a chest event")
                .isFalse();
    }

    @Test
    void blockNotChestExcludesTheChestsTransactionsAndKeepsEverythingElse() {
        QueryPredicate notChest = new QueryPredicate.Not(blockShape("CHEST"));
        assertThat(matches(notChest, deposit("DIAMOND", "CHEST", 1)))
                .as("block:!chest excludes deposits into chests - the #263 report")
                .isFalse();
        assertThat(matches(notChest, place(Material.CHEST, 3))).isFalse();
        assertThat(matches(notChest, deposit("CHEST", "BARREL", 2)))
                .as("a chest ITEM in a barrel is not a chest event")
                .isTrue();
        assertThat(matches(notChest, place(Material.DIRT, 4)))
                .as("plain block rows survive the exclusion")
                .isTrue();
    }

    @Test
    void itemFilterStaysOnTarget() {
        // i:chest keeps its item semantics: it finds the chest ITEM moving,
        // wherever it moved to - b: no longer shadows it.
        QueryPredicate itemChest = new QueryPredicate.Eq("target", "CHEST");
        assertThat(matches(itemChest, deposit("CHEST", "BARREL", 2))).isTrue();
        assertThat(matches(itemChest, deposit("DIAMOND", "CHEST", 1))).isFalse();
    }
}
