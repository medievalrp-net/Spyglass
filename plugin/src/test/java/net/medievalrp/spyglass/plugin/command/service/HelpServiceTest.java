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
        assertThat(lines.get(0)).isEqualTo("Spyglass");
        String combined = String.join("\n", lines);
        assertThat(combined)
                .contains("/omniv2 search")
                .contains("/omniv2 rollback")
                .contains("/omniv2 restore")
                .contains("/omniv2 undo")
                .contains("/omniv2 page")
                .contains("/omniv2 tool")
                .contains("/omniv2 events")
                .contains("Params:")
                .contains("Flags:");
    }
}
