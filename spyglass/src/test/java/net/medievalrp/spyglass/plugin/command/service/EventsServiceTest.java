package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.SpyglassApi;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class EventsServiceTest {

    @Test
    void rendersJoinedSortedEventNames() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.enabledEvents()).thenReturn(Set.of("place", "break", "say"));

        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        new EventsService(api).send(sender);

        List<String> lines = ServiceTestSupport.plainTexts(captured);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("Enabled Events:");
        assertThat(lines.get(1)).isEqualTo("break, place, say");
    }

    @Test
    void rendersNoneWhenEmpty() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.enabledEvents()).thenReturn(Set.of());

        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        new EventsService(api).send(sender);

        List<String> lines = ServiceTestSupport.plainTexts(captured);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("Enabled Events:");
        assertThat(lines.get(1)).isEqualTo("(none)");
    }
}
