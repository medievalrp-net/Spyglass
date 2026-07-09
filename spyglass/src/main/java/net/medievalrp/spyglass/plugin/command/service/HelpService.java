package net.medievalrp.spyglass.plugin.command.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class HelpService {

    private record Entry(String name, String usage, String description) {
    }

    // Every registered command, in rough workflow order (#249). Keep this in
    // lockstep with SpyglassCommands registrations and COMMANDS.md - a
    // command an operator can't discover here may as well not exist.
    private static final Entry[] ENTRIES = new Entry[]{
            // Page 1: the daily commands (the classic help screen).
            new Entry("help", "<Page #>", "Show this help, one page at a time."),
            new Entry("search", "<Lookup Params>", "Search Data Records based on the parameters provided."),
            new Entry("page", "<Page #>", "Moves you to the specified page of results (cached 15 min)."),
            new Entry("rollback", "<Lookup Params>", "Rollback a set of changes based on the parameters provided."),
            new Entry("restore", "<Lookup Params>", "Restore a set of changes based on the parameters provided."),
            new Entry("undo", "", "Undo your most recent rollback or restore."),
            new Entry("tool", "", "Toggle the inspection wand."),
            new Entry("events", "", "List every event type currently being recorded."),
            // Page 2: queue/admin/migration.
            new Entry("rbqueue", "", "List, cancel, or resume queued rollback jobs."),
            new Entry("inventory", "[id]", "Recover items a rollback destroyed (GUI where available)."),
            new Entry("stats", "", "Show ingest analytics (when enabled in config)."),
            new Entry("import", "<file>", "Import a CoreProtect SQLite database from plugins/Spyglass/import/."),
            new Entry("import mysql", "<source>", "Import a live CoreProtect MySQL database (sources in import.conf)."),
            new Entry("migrate", "<backend>", "Copy all records into another configured storage backend."),
            new Entry("tele", "<world> <x> <y> <z>", "Teleport (used by clickable search results)."),
            new Entry("version", "", "Show the Spyglass version."),
    };

    private static final int PAGE_SIZE = 8;

    public void send(CommandSender sender) {
        send(sender, 1);
    }

    public void send(CommandSender sender, int page) {
        int pages = (ENTRIES.length + PAGE_SIZE - 1) / PAGE_SIZE;
        int clamped = Math.min(Math.max(page, 1), pages);
        // Same red clickable nav controls the search-result header uses
        // (#260), clicking through /spyglass help <n>.
        net.kyori.adventure.text.TextComponent.Builder header = Component.text()
                .append(Component.text(
                        " -======= Spyglass (" + clamped + "/" + pages + ") =======-",
                        NamedTextColor.AQUA));
        if (clamped > 1) {
            header.append(Component.text(" ", NamedTextColor.WHITE))
                    .append(navButton("←", clamped - 1));
        }
        if (clamped < pages) {
            header.append(Component.text(" ", NamedTextColor.WHITE))
                    .append(navButton("→", clamped + 1));
        }
        sender.sendMessage(header.asComponent());
        sender.sendMessage(Component.text("For Powerful Searching", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, true));
        int from = (clamped - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, ENTRIES.length);
        for (int index = from; index < to; index++) {
            Entry entry = ENTRIES[index];
            Component line = Component.text()
                    .append(Component.text("/spyglass ", NamedTextColor.AQUA))
                    .append(Component.text(entry.name() + " ", NamedTextColor.GREEN))
                    .append(Component.text(entry.usage(), NamedTextColor.GREEN))
                    .append(Component.text(": ", NamedTextColor.AQUA))
                    .append(Component.text(entry.description(), NamedTextColor.GRAY))
                    .asComponent();
            sender.sendMessage(line);
        }
    }

    /** Mirrors ResultRenderer.navButton, targeting /spyglass help instead. */
    private static Component navButton(String arrow, int targetPage) {
        Component label = Component.text()
                .append(Component.text("[", NamedTextColor.RED))
                .append(Component.text(arrow, NamedTextColor.RED))
                .append(Component.text("]", NamedTextColor.RED))
                .asComponent();
        return label
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                        "/spyglass help " + targetPage))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text("Page " + targetPage, NamedTextColor.RED)));
    }
}
