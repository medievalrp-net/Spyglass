package net.medievalrp.omniscience2.plugin.command.service;

import net.medievalrp.omniscience2.api.Omniscience2Api;
import net.medievalrp.omniscience2.plugin.command.render.Feedback;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EventsService {

    private final Omniscience2Api api;

    public EventsService(Omniscience2Api api) {
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
