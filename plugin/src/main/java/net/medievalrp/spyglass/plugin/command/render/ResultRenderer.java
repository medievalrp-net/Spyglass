package net.medievalrp.spyglass.plugin.command.render;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
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
        Component line = Component.text()
                .append(Component.text("| ", NamedTextColor.DARK_GRAY))
                .append(Component.text(sample.sourceName(), NamedTextColor.YELLOW))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text(verb(sample), NamedTextColor.GREEN))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text("x" + count, NamedTextColor.GOLD))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text(targetOf(sample), NamedTextColor.AQUA))
                .append(Component.text(" " + timeAgo(sample.occurred()), NamedTextColor.DARK_GRAY))
                .hoverEvent(HoverEvent.showText(hover(sample, count)))
                .clickEvent(ClickEvent.runCommand("/sg search "
                        + "a:" + sample.event()
                        + " p:" + sample.sourceName()
                        + " -ng"))
                .build();
        return line;
    }

    public Component renderSingle(EventRecord record) {
        Component line = Component.text()
                .append(Component.text("| ", NamedTextColor.DARK_GRAY))
                .append(Component.text(record.sourceName(), NamedTextColor.YELLOW))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text(verb(record), NamedTextColor.GREEN))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text(targetOf(record), NamedTextColor.AQUA))
                .append(Component.text(" " + timeAgo(record.occurred()), NamedTextColor.DARK_GRAY))
                .hoverEvent(HoverEvent.showText(hover(record, 1)))
                .build();
        return line;
    }

    public Component pageHeader(int page, int totalPages) {
        return Component.text()
                .append(Component.text("« ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Spyglass", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Page " + page + "/" + totalPages, NamedTextColor.GRAY))
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
        if (record instanceof ChatRecord chat) {
            lines.add(kv("Message", chat.message()));
            if (!chat.recipients().isEmpty()) {
                lines.add(kv("Recipients", String.valueOf(chat.recipients().size())));
            }
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
            case ContainerDepositRecord deposit -> deposit.amount() + "x " + deposit.target();
            case ContainerWithdrawRecord withdraw -> withdraw.amount() + "x " + withdraw.target();
            case ChatRecord chat -> chat.message();
            case CommandRecord command -> "/" + command.target();
            case JoinRecord join -> join.target();
            case QuitRecord quit -> quit.target();
            case ItemDropRecord drop -> drop.amount() + "x " + drop.target();
            case ItemPickupRecord pickup -> pickup.amount() + "x " + pickup.target();
            case TeleportRecord tp -> tp.target() + " via " + tp.cause();
            case EntityDeathRecord death -> death.target() + " (" + death.damageCause() + ")";
            case EntityHitRecord hit -> hit.target() + " for " + String.format("%.1f", hit.damage());
            case EntityMountRecord mount -> (mount.dismount() ? "dismounted " : "mounted ") + mount.target();
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
