package net.medievalrp.omniscience2.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.medievalrp.omniscience2.api.Omniscience2Api;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class EventsServiceTest {

    @Test
    void rendersJoinedSortedEventNames() {
        Omniscience2Api api = mock(Omniscience2Api.class);
        when(api.enabledEvents()).thenReturn(Set.of("place", "break", "say"));

        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        new EventsService(api).send(sender);

        List<String> lines = ServiceTestSupport.plainTexts(captured);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo("Enabled events: break, place, say");
    }

    @Test
    void rendersNoneWhenEmpty() {
        Omniscience2Api api = mock(Omniscience2Api.class);
        when(api.enabledEvents()).thenReturn(Set.of());

        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        new EventsService(api).send(sender);

        assertThat(ServiceTestSupport.plainTexts(captured))
                .containsExactly("Enabled events: (none)");
    }
}
