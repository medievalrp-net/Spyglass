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
        assertThat(lines.get(0)).contains("v1");
        String combined = String.join("\n", lines);
        assertThat(combined)
                .contains("/sg search")
                .contains("/sg rollback")
                .contains("/sg restore")
                .contains("/sg undo")
                .contains("/sg page")
                .contains("/sg tool")
                .contains("/sg events");
    }
}
