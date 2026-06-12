package net.medievalrp.spyglass.proxy.command;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerInteractRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.EntityMountRecord;
import net.medievalrp.spyglass.api.event.EntityNameRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.QuitRecord;
import net.medievalrp.spyglass.api.event.RollbackOpRecord;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.TeleportRecord;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.util.BlockLocation;

/**
 * Adventure-only mirror of the Paper-side ResultRenderer.
 *
 * <p>Same line shape ({@code = SOURCE verb TARGET TIME}), same hover
 * fields, with two deltas:
 *
 * <ol>
 *   <li>The hover always emits a {@code Server: <name>} line because
 *       the cross-server view is the entire reason this proxy plugin
 *       exists. (The Paper renderer only emits it when non-empty.)</li>
 *   <li>Click-to-teleport is replaced with click-to-drilldown for
 *       grouped results; single results have no click action because
 *       the proxy can't teleport an operator into a backend world from
 *       this side.</li>
 * </ol>
 *
 * Past-tense verbs are baked in (vs. Paper's config-driven map) since
 * the proxy doesn't load the events config block.
 */
public final class ProxyResultRenderer {

    private static final Map<String, String> PAST_TENSE = Map.ofEntries(
            Map.entry("break", "broke"),
            Map.entry("place", "placed"),
            Map.entry("deposit", "deposited"),
            Map.entry("withdraw", "withdrew"),
            Map.entry("open", "opened"),
            Map.entry("close", "closed"),
            Map.entry("shulker-open", "opened"),
            Map.entry("shulker-close", "closed"),
            Map.entry("use", "used"),
            Map.entry("useSign", "activated"),
            Map.entry("say", "said"),
            Map.entry("command", "ran"),
            Map.entry("join", "joined"),
            Map.entry("quit", "quit"),
            Map.entry("decay", "decayed"),
            Map.entry("form", "formed"),
            Map.entry("grow", "grew"),
            Map.entry("ignite", "ignited"),
            Map.entry("drop", "dropped"),
            Map.entry("pickup", "picked up"),
            Map.entry("clone", "cloned"),
            Map.entry("teleport", "teleported"),
            Map.entry("death", "killed"),
            Map.entry("hit", "hit"),
            Map.entry("shot", "shot"),
            Map.entry("mount", "mounted"),
            Map.entry("dismount", "dismounted"),
            Map.entry("named", "renamed"),
            Map.entry("entity-deposit", "placed on"),
            Map.entry("entity-withdraw", "took from"),
            Map.entry("bookshelf-insert", "shelved"),
            Map.entry("bookshelf-remove", "unshelved"),
            Map.entry("pot-insert", "potted"),
            Map.entry("pot-remove", "unpotted"),
            Map.entry("shulker-deposit", "deposited"),
            Map.entry("shulker-withdraw", "withdrew"),
            Map.entry("bundle-insert", "bundled"),
            Map.entry("bundle-extract", "unbundled"),
            Map.entry("brush", "brushed"),
            Map.entry("sculk", "triggered"),
            Map.entry("crafter", "crafted"),
            Map.entry("vault", "unlocked"),
            Map.entry("rolled-place", "placed"),
            Map.entry("rolled-break", "broke"));

    /** Mirrors Paper's ResultRenderer.IP_HIDDEN — shown where a join IP
     *  would render when the viewer lacks {@code spyglass.search.ip}. */
    public static final String IP_HIDDEN = "(ip hidden)";

    public Component renderAggregation(QueryResult.RecordAggregation aggregation, boolean showIp) {
        return line(aggregation.sample(), aggregation.count(), true, false,
                EnumSet.noneOf(Flag.class), showIp);
    }

    public Component renderSingle(EventRecord record, EnumSet<Flag> flags, boolean showIp) {
        EnumSet<Flag> safe = flags == null ? EnumSet.noneOf(Flag.class) : flags;
        boolean extended = safe.contains(Flag.EXTENDED);
        return line(record, 1, false, extended, safe, showIp);
    }

    private Component line(EventRecord record, long count, boolean grouped, boolean extended,
                           EnumSet<Flag> flags, boolean showIp) {
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
        builder.append(Component.text(displaySourceName(record), NamedTextColor.GREEN))
                .append(Component.text(" " + verb(record), NamedTextColor.WHITE));
        int qty = quantityOf(record);
        if (qty > 0) {
            builder.append(Component.text(" " + qty, NamedTextColor.GREEN));
        }
        String targetText = targetOf(record, showIp);
        if (!targetText.isEmpty()) {
            builder.append(Component.text(" " + targetText, NamedTextColor.AQUA));
        }
        if (grouped) {
            builder.append(Component.text(" x" + count, NamedTextColor.GREEN));
        }
        builder.append(Component.text(" " + timeAgo(record.occurred()), NamedTextColor.WHITE))
                .hoverEvent(HoverEvent.showText(hover(record, count, showIp)));
        if (grouped) {
            // Drill-down: same trick as Paper's renderer. Click runs a
            // narrowed search restricted to this player + event + server,
            // bypassing aggregation so each row is visible.
            String drillCmd = "/sgv search "
                    + "a:" + record.event()
                    + " p:" + record.sourceName()
                    + " srv:" + safeServer(record.server())
                    + " -ng";
            builder.clickEvent(ClickEvent.runCommand(drillCmd));
        }
        if (extended && record.location() != null) {
            builder.append(Component.newline())
                    .append(Component.text(
                            " - (x: " + record.location().x()
                                    + " y: " + record.location().y()
                                    + " z: " + record.location().z()
                                    + " world: " + record.location().worldName()
                                    + " server: " + safeServer(record.server()) + ")",
                            NamedTextColor.GRAY));
        }
        return builder.build();
    }

    private Component hover(EventRecord record, long count, boolean showIp) {
        List<Component> lines = new ArrayList<>();
        lines.add(kv("Source", record.sourceName()));
        lines.add(kv("Event", record.event()));
        if (count > 1) {
            lines.add(kv("Count", String.valueOf(count)));
        }
        lines.add(kv("Target", targetOf(record, showIp)));
        lines.add(kv("When", fullTimestamp(record.occurred())));
        lines.add(kv("Origin", originText(record.origin())));
        lines.add(kv("Location", locationText(record.location())));
        // Server is always emitted on the proxy: the cross-server view
        // is the whole point. Empty values fall back to "(unset)" so an
        // operator can spot legacy rows from before the server-tag
        // landed.
        lines.add(kv("Server", record.server() == null || record.server().isBlank()
                ? "(unset)" : record.server()));
        if (record instanceof ChatRecord chat && !chat.recipients().isEmpty()) {
            lines.add(kv("Recipients", chat.recipients().size() + " players"));
        }
        if (record instanceof CommandRecord command) {
            lines.add(kv("Line", command.commandLine()));
        }
        if (record instanceof JoinRecord join && join.address() != null && !join.address().isBlank()) {
            lines.add(kv("IP", showIp ? join.address() : IP_HIDDEN));
        }
        if (record instanceof EntityHitRecord hit) {
            lines.add(kv("Damage", String.format(Locale.ROOT, "%.1f", hit.damage())));
            if (hit.projectile()) {
                lines.add(kv("Weapon", hit.projectileType() == null
                        ? "projectile" : hit.projectileType().toUpperCase(Locale.ROOT)));
            }
        }
        if (record instanceof EntityDeathRecord death && death.damageCause() != null
                && !death.damageCause().isBlank()) {
            lines.add(kv("Cause", death.damageCause()));
        }
        if (record instanceof TeleportRecord tp && tp.cause() != null && !tp.cause().isBlank()) {
            lines.add(kv("Via", tp.cause()));
        }

        Component hover = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                hover = hover.append(Component.newline());
            }
            hover = hover.append(lines.get(i));
        }
        return hover;
    }

    private static String verb(EventRecord record) {
        return PAST_TENSE.getOrDefault(record.event(), record.event());
    }

    private static String displaySourceName(EventRecord record) {
        Source source = record.source();
        String name = record.sourceName();
        if (source == null || name == null) {
            return name;
        }
        if (Source.PLAYER.equals(source.kind())) {
            return name;
        }
        return name.toUpperCase(Locale.ROOT);
    }

    private static String safeServer(String server) {
        return server == null || server.isBlank() ? "?" : server;
    }

    private static String upperOrEmpty(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static String withContainer(String item, String containerType) {
        if (containerType == null || containerType.isBlank()) {
            return item;
        }
        return item + " IN " + containerType;
    }

    private static String targetOf(EventRecord record, boolean showIp) {
        return switch (record) {
            case BlockBreakRecord r -> r.target();
            case BlockPlaceRecord r -> r.target();
            case ContainerDepositRecord r -> withContainer(r.target(), r.containerType());
            case ContainerWithdrawRecord r -> withContainer(r.target(), r.containerType());
            case ContainerInteractRecord r -> r.target();
            case BlockUseRecord r -> r.target();
            case RollbackOpRecord r -> r.mode() == null ? "" : r.mode().toUpperCase(java.util.Locale.ROOT);
            case ChatRecord r -> r.target();
            case CommandRecord r -> "/" + r.target();
            case JoinRecord r -> r.address() == null || r.address().isEmpty()
                    ? ""
                    : (showIp ? r.address() : IP_HIDDEN);
            case QuitRecord r -> "";
            case ItemDropRecord r -> r.target();
            case ItemPickupRecord r -> r.target();
            case TeleportRecord r -> r.target() + " via " + r.cause();
            case EntityDeathRecord r -> upperOrEmpty(r.target());
            case EntityHitRecord r -> upperOrEmpty(r.target());
            case EntityMountRecord r -> (r.dismount() ? "dismounted " : "mounted ") + upperOrEmpty(r.target());
            case EntityNameRecord r -> {
                String arrow = r.oldName() == null
                        ? "→ " + r.newName()
                        : "'" + r.oldName() + "' → '" + r.newName() + "'";
                yield upperOrEmpty(r.target()) + " " + arrow;
            }
        };
    }

    private static int quantityOf(EventRecord record) {
        return switch (record) {
            case ContainerDepositRecord r -> r.amount();
            case ContainerWithdrawRecord r -> r.amount();
            case ItemDropRecord r -> r.amount();
            case ItemPickupRecord r -> r.amount();
            default -> 0;
        };
    }

    private static String originTag(Origin origin) {
        if (origin == null || origin.kind() == null) {
            return "";
        }
        return switch (origin.kind().toLowerCase(Locale.ROOT)) {
            case Origin.WORLDEDIT -> "[WE]";
            case Origin.FAWE -> "[FAWE]";
            case Origin.PLUGIN -> "[" + (origin.detail() == null
                    ? "PL" : origin.detail().toUpperCase(Locale.ROOT)) + "]";
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

    private static String shortDate(Instant occurred) {
        return occurred.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d/M/yy"));
    }

    private static String fullTimestamp(Instant instant) {
        return instant.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
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

    public static Component pageHeader(int page, int totalPages, int totalResults) {
        var builder = Component.text()
                .append(Component.text("«", NamedTextColor.GREEN))
                .append(Component.text("Spyglass", NamedTextColor.AQUA))
                .append(Component.text("»", NamedTextColor.GREEN))
                .append(Component.text(" ", NamedTextColor.WHITE))
                .append(Component.text(
                        "(page " + page + "/" + totalPages + " - " + totalResults + " results)",
                        NamedTextColor.GRAY));
        if (page > 1) {
            builder.append(Component.text(" ", NamedTextColor.WHITE))
                    .append(navButton("←", page - 1));
        }
        if (page < totalPages) {
            builder.append(Component.text(" ", NamedTextColor.WHITE))
                    .append(navButton("→", page + 1));
        }
        return builder.build();
    }

    private static Component navButton(String arrow, int targetPage) {
        return Component.text()
                .append(Component.text("[", NamedTextColor.RED))
                .append(Component.text(arrow, NamedTextColor.RED))
                .append(Component.text("]", NamedTextColor.RED))
                .build()
                .clickEvent(ClickEvent.runCommand("/sgv page " + targetPage))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Page " + targetPage, NamedTextColor.RED)));
    }
}
