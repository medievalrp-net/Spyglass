package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.plugin.command.render.Feedback;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EventsService {

    private final SpyglassApi api;

    public EventsService(SpyglassApi api) {
        this.api = api;
    }

    public void send(CommandSender sender) {
        String joined = api.enabledEvents().stream().sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");
        sender.sendMessage(Feedback.success("Enabled Events: "));
        sender.sendMessage(Feedback.bonus(joined));
    }
}
