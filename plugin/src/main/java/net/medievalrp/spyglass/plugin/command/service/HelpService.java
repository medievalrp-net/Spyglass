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

    private static final Entry[] ENTRIES = new Entry[]{
            new Entry("search", "<Lookup Params>", "Search Data Records based on the parameters provided."),
            new Entry("rollback", "<Lookup Params>", "Rollback a set of changes based on the parameters provided."),
            new Entry("restore", "<Lookup Params>", "Restore a set of changes based on the parameters provided."),
            new Entry("undo", "", "Undo your most recent rollback or restore."),
            new Entry("page", "<Page #>", "Moves you to the specified page of results, if available."),
            new Entry("tool", "", "Toggle the inspection wand."),
            new Entry("events", "", "List every event type currently being recorded."),
    };

    public void send(CommandSender sender) {
        sender.sendMessage(Component.text(" -======= v1 =======-", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("For Powerful Searching", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, true));
        for (Entry entry : ENTRIES) {
            Component line = Component.text()
                    .append(Component.text("/sg ", NamedTextColor.AQUA))
                    .append(Component.text(entry.name() + " ", NamedTextColor.GREEN))
                    .append(Component.text(entry.usage(), NamedTextColor.GREEN))
                    .append(Component.text(": ", NamedTextColor.AQUA))
                    .append(Component.text(entry.description(), NamedTextColor.GRAY))
                    .build();
            sender.sendMessage(line);
        }
    }
}
