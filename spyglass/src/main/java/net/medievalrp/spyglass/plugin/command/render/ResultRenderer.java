package net.medievalrp.spyglass.plugin.command.render;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.SpyglassApi;
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
import net.medievalrp.spyglass.api.extension.DisplayRenderer;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ResultRenderer {

    private final SpyglassApi api;
    private final SpyglassConfig config;

    public ResultRenderer(SpyglassApi api, SpyglassConfig config) {
        this.api = api;
        this.config = config;
    }

    /** Stand-in shown where a join IP would render when the viewer
     *  lacks {@code spyglass.search.ip} (#48). */
    public static final String IP_HIDDEN = "(ip hidden)";

    public Component renderAggregation(QueryResult.RecordAggregation aggregation, boolean showIp) {
        EventRecord sample = aggregation.sample();
        long count = aggregation.count();
        // Aggregations span multiple locations; `-ex` location-append is
        // skipped because any one sample.location() would be misleading.
        return line(sample, count, true, false, java.util.EnumSet.noneOf(
                net.medievalrp.spyglass.api.query.Flag.class), showIp);
    }

    public Component renderSingle(EventRecord record, java.util.EnumSet<net.medievalrp.spyglass.api.query.Flag> flags,
                                  boolean showIp) {
        java.util.EnumSet<net.medievalrp.spyglass.api.query.Flag> safe = flags == null
                ? java.util.EnumSet.noneOf(net.medievalrp.spyglass.api.query.Flag.class)
                : flags;
        boolean extended = safe.contains(net.medievalrp.spyglass.api.query.Flag.EXTENDED);
        return line(record, 1, false, extended, safe, showIp);
    }

    private Component line(EventRecord record, long count, boolean grouped, boolean extended,
                           java.util.EnumSet<net.medievalrp.spyglass.api.query.Flag> flags,
                           boolean showIp) {
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
        builder.append(Component.text(displaySourceName(record), NamedTextColor.GREEN))
                .append(Component.text(" " + verb(record), NamedTextColor.WHITE));
        int qty = quantityOf(record);
        if (qty > 0) {
            builder.append(Component.text(" " + qty, NamedTextColor.GREEN));
        }
        // Target span: skipped entirely when the record has nothing
        // useful to put there (e.g. QuitRecord), otherwise rendered as
        // " TARGET" with a single leading space so the line stays
        // tidy regardless of whether quantity/target are present.
        String targetText = targetOf(record, showIp);
        if (!targetText.isEmpty()) {
            Component defaultTarget = Component.text(" " + targetText, NamedTextColor.AQUA);
            java.util.Optional<DisplayRenderer> custom = api == null
                    ? java.util.Optional.empty()
                    : api.displayRenderer(record.event());
            Component target = custom
                    .map(renderer -> {
                        try {
                            return renderer.renderTarget(record, defaultTarget, flags);
                        } catch (RuntimeException ex) {
                            return defaultTarget;
                        }
                    })
                    .orElse(defaultTarget);
            builder.append(target);
        }
        if (grouped) {
            builder.append(Component.text(" x" + count, NamedTextColor.GREEN));
        }
        builder.append(Component.text(" " + timeAgo(record.occurred()), NamedTextColor.WHITE))
                .hoverEvent(HoverEvent.showText(hover(record, count, showIp)));
        if (grouped) {
            // Grouped result: click drills down into the per-player,
            // per-event stream. Matches v1's buildDetailCommand shape.
            builder.clickEvent(ClickEvent.runCommand("/spyglass search "
                    + "a:" + record.event()
                    + " p:" + record.sourceName()
                    + " -ng"));
        } else if (record.location() != null) {
            // Single record: click teleports the operator to the scene.
            builder.clickEvent(teleportClick(record.location()));
        }
        if (extended && record.location() != null) {
            // v1's `-ex`: a second inline gray line showing coordinates,
            // also clickable for teleport (redundant with the main line
            // click but consistent with v1's DataHelper.buildLocation).
            builder.append(Component.newline())
                    .append(Component.text(
                                    " - (x: " + record.location().x()
                                            + " y: " + record.location().y()
                                            + " z: " + record.location().z()
                                            + " world: " + record.location().worldName() + ")",
                                    NamedTextColor.GRAY)
                            .clickEvent(teleportClick(record.location()))
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("Click to teleport", NamedTextColor.GRAY))));
        }
        return builder.build();
    }

    private static ClickEvent teleportClick(BlockLocation loc) {
        return ClickEvent.runCommand("/spyglass tele "
                + loc.worldId() + " " + loc.x() + " " + loc.y() + " " + loc.z());
    }

    private static String shortDate(Instant occurred) {
        return occurred.atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d/M/yy"));
    }

    public static Component pageHeader(int page, int totalPages, int totalResults) {
        // Each arrow only renders when there's a page to go to. On page 1
        // the left disappears entirely; on the last page the right does.
        // Single-page results show no arrows at all.
        net.kyori.adventure.text.TextComponent.Builder builder = Component.text()
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
        Component label = Component.text()
                .append(Component.text("[", NamedTextColor.RED))
                .append(Component.text(arrow, NamedTextColor.RED))
                .append(Component.text("]", NamedTextColor.RED))
                .build();
        return label
                .clickEvent(ClickEvent.runCommand("/spyglass page " + targetPage))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Page " + targetPage, NamedTextColor.RED)));
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
        if (record.server() != null && !record.server().isBlank()) {
            lines.add(kv("Server", record.server()));
        }
        if (record instanceof ChatRecord chat && !chat.recipients().isEmpty()) {
            lines.add(kv("Recipients", recipientNames(chat.recipients())));
        }
        if (record instanceof CommandRecord command) {
            lines.add(kv("Line", command.commandLine()));
        }
        if (record instanceof JoinRecord join && !join.address().isBlank()) {
            lines.add(kv("IP", showIp ? join.address() : IP_HIDDEN));
        }
        if (record instanceof EntityHitRecord hit) {
            lines.add(kv("Damage", String.format(Locale.ROOT, "%.1f", hit.damage())));
            if (hit.projectile()) {
                lines.add(kv("Weapon", hit.projectileType() == null
                        ? "projectile" : upperOrEmpty(hit.projectileType())));
            }
        }
        if (record instanceof EntityDeathRecord death && death.damageCause() != null
                && !death.damageCause().isBlank()) {
            lines.add(kv("Cause", death.damageCause()));
        }
        if (record instanceof TeleportRecord tp && tp.cause() != null && !tp.cause().isBlank()) {
            lines.add(kv("Via", tp.cause()));
        }
        // Append extra hover lines from a registered DisplayRenderer, if any.
        if (api != null) {
            api.displayRenderer(record.event()).ifPresent(renderer -> {
                try {
                    lines.addAll(renderer.hoverLines(record));
                } catch (RuntimeException ignored) {
                }
            });
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

    /**
     * Source-name display rule: keep player names verbatim (case
     * matters — "Itdontmatta" is the actual identity), but uppercase
     * everything else (entity types, env descriptions like "fire" /
     * "lava", console, command_block) so they read as identifiers
     * matching the uppercase target style ("FIRE broke SHORT_GRASS"
     * rather than "fire broke SHORT_GRASS").
     */
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

    /** Defensive uppercase for entity-type / target tokens that are
     *  conventionally uppercase but might come back lowercase from
     *  legacy data. */
    private static String upperOrEmpty(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    /** "ITEM IN CONTAINER" — appends the container type to the
     *  deposit / withdraw target so the line reads like
     *  "itdontmatta deposited ACTIVATOR_RAIL IN HOPPER". Falls
     *  back to just the item if container type is unknown. */
    private static String withContainer(String item, String containerType) {
        if (containerType == null || containerType.isBlank()) {
            return item;
        }
        return item + " IN " + containerType;
    }

    private static String targetOf(EventRecord record, boolean showIp) {
        return switch (record) {
            case BlockBreakRecord breakRec -> breakRec.target();
            case BlockPlaceRecord placeRec -> placeRec.target();
            case ContainerDepositRecord deposit -> withContainer(deposit.target(), deposit.containerType());
            case ContainerWithdrawRecord withdraw -> withContainer(withdraw.target(), withdraw.containerType());
            case ContainerInteractRecord interact -> interact.target();
            case BlockUseRecord use -> use.target();
            // One row per rollback/restore/undo operation (#22); the
            // mode rides in target. Renders "<operator> ran ROLLBACK".
            case RollbackOpRecord op -> upperOrEmpty(op.mode());
            case ChatRecord chat -> chat.target();
            case CommandRecord command -> "/" + command.target();
            // Join's "target" in the data model is the player's own
            // name, which duplicates the source — show the IP instead
            // so the line reads "<player> joined <ip>". Quit has no
            // companion field so the target span gets suppressed
            // entirely (the line() builder skips empty targets).
            case JoinRecord join -> join.address() == null || join.address().isEmpty()
                    ? ""
                    : (showIp ? join.address() : IP_HIDDEN);
            case QuitRecord quit -> "";
            case ItemDropRecord drop -> drop.target();
            case ItemPickupRecord pickup -> pickup.target();
            case TeleportRecord tp -> tp.target() + " via " + tp.cause();
            // Entity events: bukkit EntityType names are conventionally
            // uppercase (ZOMBIE, SNIFFER, etc.). Display lowercase /
            // mixed-case incoming values uppercased for consistency.
            // Damage / damage-cause / projectile detail moves to the
            // hover so the inline form matches "<src> hit TARGET TIME"
            // — not "<src> hit TARGET for 1.0 TIME".
            case EntityDeathRecord death -> upperOrEmpty(death.target());
            case EntityHitRecord hit -> upperOrEmpty(hit.target());
            case EntityMountRecord mount ->
                    (mount.dismount() ? "dismounted " : "mounted ") + upperOrEmpty(mount.target());
            case EntityNameRecord named -> {
                String arrow = named.oldName() == null
                        ? "→ " + named.newName()
                        : "'" + named.oldName() + "' → '" + named.newName() + "'";
                yield upperOrEmpty(named.target()) + " " + arrow;
            }
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
        return switch (origin.kind().toLowerCase(java.util.Locale.ROOT)) {
            case Origin.WORLDEDIT -> "[WE]";
            case Origin.FAWE -> "[FAWE]";
            case Origin.PLUGIN -> "[" + (origin.detail() == null ? "PL" : origin.detail()) + "]";
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
