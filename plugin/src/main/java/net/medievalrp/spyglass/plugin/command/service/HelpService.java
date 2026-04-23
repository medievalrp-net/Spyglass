package net.medievalrp.spyglass.plugin.command.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

public final class HelpService {

    private static final String[] LINES = new String[]{
            "  /omniv2 search <params>",
            "  /omniv2 rollback <params>",
            "  /omniv2 restore <params>",
            "  /omniv2 undo",
            "  /omniv2 page <n>",
            "  /omniv2 tool",
            "  /omniv2 events"
    };

    public void send(CommandSender sender) {
        sender.sendMessage(Component.text("Spyglass", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true));
        for (String line : LINES) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
        sender.sendMessage(Component.text("Params: p: a: r: t: b: e: w:", NamedTextColor.DARK_GRAY));
        sender.sendMessage(Component.text("Flags: -ng -g -nc -ex -ord=asc|desc", NamedTextColor.DARK_GRAY));
    }
}
