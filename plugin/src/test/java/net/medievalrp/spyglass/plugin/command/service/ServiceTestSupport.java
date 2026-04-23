package net.medievalrp.spyglass.plugin.command.service;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Helpers shared across service tests for capturing and asserting against Adventure messages
 * delivered to mocked senders.
 */
final class ServiceTestSupport {

    private ServiceTestSupport() {
    }

    static List<Component> captureMessages(CommandSender sender) {
        List<Component> captured = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return null;
        }).when(sender).sendMessage(ArgumentMatchers.any(Component.class));
        return captured;
    }

    static List<String> plainTexts(List<Component> components) {
        List<String> out = new ArrayList<>(components.size());
        for (Component c : components) {
            out.add(PlainTextComponentSerializer.plainText().serialize(c));
        }
        return out;
    }
}
