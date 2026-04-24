package net.medievalrp.spyglass.plugin.command.render;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerInteractRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.EntityMountRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.QuitRecord;
import net.medievalrp.spyglass.api.event.TeleportRecord;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ResultRenderer {

    private final SpyglassConfig config;

    public ResultRenderer(SpyglassConfig config) {
        this.config = config;
    }

    public Component renderAggregation(QueryResult.RecordAggregation aggregation) {
        EventRecord sample = aggregation.sample();
        long count = aggregation.count();
        return line(sample, count, true);
    }

    public Component renderSingle(EventRecord record) {
        return line(record, 1, false);
    }

    private Component line(EventRecord record, long count, boolean grouped) {
        // v1 grouped: "= (24/4/26) SOURCE verb [qty ]TARGET xCOUNT TIME"
        // v1 ungrouped: "= SOURCE verb [qty ]TARGET TIME"
        var builder = Component.text()
                .append(Component.text("= ", NamedTextColor.GRAY));
        if (grouped) {
            builder.append(Component.text("(" + shortDate(record.occurred()) + ") ",
                    NamedTextColor.GRAY));
        }
        String tag = originTag(record.origin());
        if (!tag.isEmpty()) {
            builder.append(Component.text(tag + " ", NamedTextColor.AQUA));
        }
        builder.append(Component.text(record.sourceName(), NamedTextColor.GREEN))
                .append(Component.text(" " + verb(record) + " ", NamedTextColor.WHITE));
        int qty = quantityOf(record);
        if (qty > 0) {
            builder.append(Component.text(qty + " ", NamedTextColor.GREEN));
        }
        builder.append(Component.text(targetOf(record), NamedTextColor.AQUA));
        if (grouped) {
            builder.append(Component.text(" x" + count, NamedTextColor.GREEN));
        }
        builder.append(Component.text(" " + timeAgo(record.occurred()), NamedTextColor.WHITE))
                .hoverEvent(HoverEvent.showText(hover(record, count)));
        if (grouped) {
            builder.clickEvent(ClickEvent.runCommand("/sg search "
                    + "a:" + record.event()
                    + " p:" + record.sourceName()
                    + " -ng"));
        }
        return builder.build();
    }

    private static String shortDate(Instant occurred) {
        return occurred.atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d/M/yy"));
    }

    public static Component pageHeader(int page, int totalPages, int totalResults) {
        return Component.text()
                .append(Component.text("«", NamedTextColor.WHITE))
                .append(Component.text("v1", NamedTextColor.AQUA))
                .append(Component.text("»", NamedTextColor.GREEN))
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(Component.text(
                        "((Page " + page + "/" + totalPages + " — " + totalResults + " results))",
                        NamedTextColor.GRAY))
                .build();
    }

    private Component hover(EventRecord record, long count) {
        List<Component> lines = new ArrayList<>();
        lines.add(kv("Source", record.sourceName()));
        lines.add(kv("Event", record.event()));
        if (count > 1) {
            lines.add(kv("Count", String.valueOf(count)));
        }
        lines.add(kv("Target", targetOf(record)));
        lines.add(kv("When", fullTimestamp(record.occurred())));
        lines.add(kv("Origin", originText(record.origin())));
        lines.add(kv("Location", locationText(record.location())));
        if (record instanceof ChatRecord chat && !chat.recipients().isEmpty()) {
            lines.add(kv("Recipients", recipientNames(chat.recipients())));
        }
        if (record instanceof CommandRecord command) {
            lines.add(kv("Line", command.commandLine()));
        }
        if (record instanceof JoinRecord join && !join.address().isBlank()) {
            lines.add(kv("IP", join.address()));
        }

        Component hover = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                hover = hover.append(Component.newline());
            }
            hover = hover.append(lines.get(index));
        }
        return hover;
    }

    private String verb(EventRecord record) {
        return config.pastTense(record.event());
    }

    private static String targetOf(EventRecord record) {
        return switch (record) {
            case BlockBreakRecord breakRec -> breakRec.target();
            case BlockPlaceRecord placeRec -> placeRec.target();
            case ContainerDepositRecord deposit -> deposit.target();
            case ContainerWithdrawRecord withdraw -> withdraw.target();
            case ContainerInteractRecord interact -> interact.target();
            case BlockUseRecord use -> use.target();
            case ChatRecord chat -> chat.target();
            case CommandRecord command -> "/" + command.target();
            case JoinRecord join -> join.target();
            case QuitRecord quit -> quit.target();
            case ItemDropRecord drop -> drop.target();
            case ItemPickupRecord pickup -> pickup.target();
            case TeleportRecord tp -> tp.target() + " via " + tp.cause();
            case EntityDeathRecord death -> death.target() + " (" + death.damageCause() + ")";
            case EntityHitRecord hit -> hit.target() + " for " + String.format("%.1f", hit.damage());
            case EntityMountRecord mount -> (mount.dismount() ? "dismounted " : "mounted ") + mount.target();
        };
    }

    private static int quantityOf(EventRecord record) {
        return switch (record) {
            case ContainerDepositRecord deposit -> deposit.amount();
            case ContainerWithdrawRecord withdraw -> withdraw.amount();
            case ItemDropRecord drop -> drop.amount();
            case ItemPickupRecord pickup -> pickup.amount();
            default -> 0;
        };
    }

    private static String originTag(Origin origin) {
        if (origin == null || origin.kind() == null) {
            return "";
        }
        return switch (origin.kind().toLowerCase()) {
            case Origin.WORLDEDIT -> "[WE]";
            case Origin.FAWE -> "[FAWE]";
            case Origin.PLUGIN -> "[" + (origin.detail() == null ? "PL" : origin.detail().toUpperCase()) + "]";
            default -> "";
        };
    }

    private static Component kv(String key, String value) {
        return Component.text()
                .append(Component.text(key + ": ", NamedTextColor.GRAY))
                .append(Component.text(value, NamedTextColor.WHITE))
                .build();
    }

    private static String locationText(BlockLocation location) {
        if (location == null) {
            return "(unknown)";
        }
        return location.worldName() + " " + location.x() + "," + location.y() + "," + location.z();
    }

    private static String originText(Origin origin) {
        if (origin == null || origin.kind() == null) {
            return "unknown";
        }
        String detail = origin.detail();
        return detail == null || detail.isEmpty() ? origin.kind() : origin.kind() + ":" + detail;
    }

    /**
     * Resolve recipient UUIDs to names via Bukkit's offline-player cache. If a
     * name isn't known (player never joined on this server) we fall back to a
     * short UUID so the hover never says blank.
     */
    private static String recipientNames(java.util.List<java.util.UUID> recipients) {
        if (recipients.size() > 8) {
            return recipients.size() + " players";
        }
        StringBuilder out = new StringBuilder();
        for (java.util.UUID id : recipients) {
            if (out.length() > 0) {
                out.append(", ");
            }
            String name = org.bukkit.Bukkit.getOfflinePlayer(id).getName();
            out.append(name != null ? name : id.toString().substring(0, 8));
        }
        return out.toString();
    }

    private static String timeAgo(Instant occurred) {
        long seconds = Math.max(0L, Instant.now().getEpochSecond() - occurred.getEpochSecond());
        if (seconds < 5) return "just now";
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 7) return days + "d ago";
        long weeks = days / 7;
        return weeks + "w ago";
    }

    private static String fullTimestamp(Instant instant) {
        return instant.atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
