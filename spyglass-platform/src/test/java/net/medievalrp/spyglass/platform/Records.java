package net.medievalrp.spyglass.platform;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RollbackOpRecord;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Material;

/** Record fixtures. Shared, because several tests need the same ones. */
final class Records {

    static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final BlockLocation AT = new BlockLocation(
            UUID.fromString("66666666-7777-8888-9999-000000000000"), "world", 100, 64, -200);
    static final Instant WHEN = Instant.parse("2026-10-20T12:00:00Z");
    static final Instant EXPIRES = WHEN.plusSeconds(90 * 86400);

    private Records() {
    }

    static Source player() {
        return Source.player(PLAYER, "TheOperator");
    }

    static BlockBreakRecord blockBreak(String event, Source source, Origin origin) {
        return new BlockBreakRecord(UUID.randomUUID(), event, WHEN, EXPIRES, origin, source,
                AT, "srv:test", "minecraft:stone",
                new BlockSnapshot(Material.STONE, "minecraft:stone", List.of(), List.of(),
                        List.of(), List.of(), null, List.of(), true),
                new BlockSnapshot(Material.AIR, "minecraft:air", List.of(), List.of(),
                        List.of(), List.of(), null, List.of(), true));
    }

    static ChatRecord chat(String message) {
        return new ChatRecord(UUID.randomUUID(), "say", WHEN, EXPIRES, Origin.player(),
                player(), AT, "srv:test", "", message,
                List.of(UUID.randomUUID(), UUID.randomUUID()), Map.of("channel", "town"));
    }

    static CommandRecord command(String line) {
        return new CommandRecord(UUID.randomUUID(), "command", WHEN, EXPIRES, Origin.player(),
                player(), AT, "srv:test", "", line);
    }

    static ContainerWithdrawRecord withdraw(int amount) {
        StoredItem item = new StoredItem(3, "minecraft:diamond", null, "Shiny",
                List.of("lore"), List.of("minecraft:sharpness:5"), null);
        return new ContainerWithdrawRecord(UUID.randomUUID(), "withdraw", WHEN, EXPIRES,
                Origin.player(), player(), AT, "srv:test", "minecraft:diamond",
                "minecraft:chest", 3, amount, item, null);
    }

    static EntityHitRecord hit(double damage) {
        return new EntityHitRecord(UUID.randomUUID(), "hit", WHEN, EXPIRES, Origin.player(),
                player(), AT, "srv:test", "", "minecraft:player", UUID.randomUUID(),
                damage, false, "");
    }

    /** The one record that carries an IP. */
    static JoinRecord join(String address) {
        return new JoinRecord(UUID.randomUUID(), "join", WHEN, EXPIRES, Origin.player(),
                player(), AT, "srv:test", "", address);
    }

    static RollbackOpRecord rollbackOp() {
        return new RollbackOpRecord(UUID.randomUUID(), "rollback-op", WHEN, EXPIRES,
                Origin.rollback("TheOperator"), player(), AT, "srv:test", "rollback",
                "job-7f3a");
    }
}
