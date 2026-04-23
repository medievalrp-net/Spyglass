package net.medievalrp.omniscience2.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class ToolServiceTest {

    @Test
    void placeholderMessageUntilBlock5() {
        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        new ToolService().toggle(sender);

        assertThat(ServiceTestSupport.plainTexts(captured))
                .hasSize(1)
                .anyMatch(line -> line.toLowerCase().contains("tool"));
    }
}
