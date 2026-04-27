package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class HelpServiceTest {

    @Test
    void sendsTitleCommandsAndFlags() {
        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        new HelpService().send(sender);

        List<String> lines = ServiceTestSupport.plainTexts(captured);
        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).contains("Spyglass");
        String combined = String.join("\n", lines);
        assertThat(combined)
                .contains("/spyglass search")
                .contains("/spyglass rollback")
                .contains("/spyglass restore")
                .contains("/spyglass undo")
                .contains("/spyglass page")
                .contains("/spyglass tool")
                .contains("/spyglass events");
    }
}
