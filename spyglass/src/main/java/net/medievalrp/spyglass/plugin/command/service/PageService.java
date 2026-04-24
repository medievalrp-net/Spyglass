package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.plugin.command.render.Feedback;

import net.medievalrp.spyglass.plugin.command.PageCache;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class PageService {

    private final PageCache cache;

    public PageService(PageCache cache) {
        this.cache = cache;
    }

    public void show(CommandSender sender, int page) {
        if (!cache.show(sender, page)) {
            sender.sendMessage(Feedback.warn("No active search results."));
        }
    }
}
