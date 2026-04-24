package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.plugin.command.PageCache;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class PageServiceTest {

    @Test
    void delegatesToCache() {
        PageCache cache = mock(PageCache.class);
        CommandSender sender = mock(CommandSender.class);
        when(cache.show(sender, 2)).thenReturn(true);

        new PageService(cache).show(sender, 2);

        verify(cache).show(sender, 2);
    }

    @Test
    void warnsOnMissingResults() {
        PageCache cache = mock(PageCache.class);
        CommandSender sender = mock(CommandSender.class);
        when(cache.show(org.mockito.ArgumentMatchers.eq(sender), anyInt())).thenReturn(false);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        new PageService(cache).show(sender, 1);

        assertThat(ServiceTestSupport.plainTexts(captured))
                .anyMatch(line -> line.contains("No active search results."));
    }
}
