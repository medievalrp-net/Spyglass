package net.medievalrp.spyglass.plugin.command.service;

import org.bukkit.command.CommandSender;

public final class ToolService {

    public void toggle(CommandSender sender) {
        sender.sendMessage(ServiceSupport.warnMessage("Inspection tool lands in Block 5 of Phase 2."));
    }
}
