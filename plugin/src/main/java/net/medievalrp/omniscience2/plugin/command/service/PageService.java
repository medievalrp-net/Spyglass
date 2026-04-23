package net.medievalrp.omniscience2.plugin.command.service;

import net.medievalrp.omniscience2.plugin.command.PageCache;
import org.bukkit.command.CommandSender;

public final class PageService {

    private final PageCache cache;

    public PageService(PageCache cache) {
        this.cache = cache;
    }

    public void show(CommandSender sender, int page) {
        if (!cache.show(sender, page)) {
            sender.sendMessage(ServiceSupport.warnMessage("No active search results."));
        }
    }
}
