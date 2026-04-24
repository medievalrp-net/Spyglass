package net.medievalrp.spyglass.plugin.command.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class HelpService {

    private static final String[] LINES = new String[]{
            " /sg search <params>",
            " /sg rollback <params>",
            " /sg restore <params>",
            " /sg undo",
            " /sg page <n>",
            " /sg tool",
            " /sg events"
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
