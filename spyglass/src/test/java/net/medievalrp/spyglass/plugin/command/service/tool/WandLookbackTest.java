package net.medievalrp.spyglass.plugin.command.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.command.service.SearchService;
import net.medievalrp.spyglass.plugin.command.service.ToolService;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Regression tests for #271: the wand's lookback comes from
 * {@code tool.lookback} (it was a hardcoded, invisible 7d), and the
 * inspect header always names the window so an empty result reads as
 * "nothing inside the window" rather than "no history exists".
 */
class WandLookbackTest {

    @Test
    void wandQueriesTheConfiguredWindowAndHeaderNamesIt() throws Exception {
        SpyglassConfig config = mock(SpyglassConfig.class);
        when(config.limits()).thenReturn(new SpyglassConfig.Limits(
                250, 1_000, 50, 4_000, Duration.parse("30s"), 40L));
        when(config.tool()).thenReturn(new SpyglassConfig.Tool(
                Material.GLOWSTONE, Duration.parse("3w")));

        SearchService search = mock(SearchService.class);
        WandInteractListener listener = new WandInteractListener(
                mock(ToolService.class), search, config);

        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("world");
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.STONE);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        Location location = new Location(world, 1, 64, 2);

        Player player = mock(Player.class);
        List<Component> messages = new ArrayList<>();
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return null;
        }).when(player).sendMessage(any(Component.class));

        // Drive the single query path both wand handlers share; the wand
        // item/PDC gate is not under test.
        Instant before = Instant.now();
        Method queryAt = WandInteractListener.class
                .getDeclaredMethod("queryAt", Player.class, Location.class);
        queryAt.setAccessible(true);
        queryAt.invoke(listener, player, location);
        Instant after = Instant.now();

        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(search).executeRequest(any(Player.class), request.capture());
        Instant floor = request.getValue().predicates().stream()
                .filter(p -> p instanceof QueryPredicate.Range r && "occurred".equals(r.field()))
                .map(p -> (Instant) ((QueryPredicate.Range) p).lowerInclusive())
                .findFirst()
                .orElseThrow(() -> new AssertionError("wand query has no occurred floor"));

        assertThat(floor)
                .as("the wand honors tool.lookback, not a hardcoded constant")
                .isBetween(before.minus(21, ChronoUnit.DAYS).minusSeconds(60),
                        after.minus(21, ChronoUnit.DAYS).plusSeconds(60));

        String header = messages.stream()
                .map(c -> PlainTextComponentSerializer.plainText().serialize(c))
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(header).contains("STONE");
        assertThat(header)
                .as("the inspect header must name the window")
                .contains("last 3w");
    }

    @Test
    void defaultLookbackIsLongNotSevenDays() {
        // The unexamined 7d constant is gone: an absent tool.lookback
        // resolves to 26w.
        assertThat(new SpyglassConfig.Tool(Material.GLOWSTONE, null).lookback())
                .isEqualTo(Duration.parse("26w"));
    }
}
