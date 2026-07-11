package net.medievalrp.spyglass.plugin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * End-to-end boot-path proof for the removed-knob migrations (#307, #312):
 * a real pre-removal config.conf on disk, loaded through the real
 * {@link SpyglassConfig#load}, must enable the plugin with every operator
 * setting intact - not reset, not disabled - while the dead keys are
 * dropped, the version is stamped, and the original file is backed up.
 */
class SpyglassConfigLoadMigrationTest {

    private static JavaPlugin pluginIn(Path dataFolder) {
        // Mockito 5's inline mock maker stubs JavaPlugin's final accessors.
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        // Fresh stream per call: load() reads the bundled template twice
        // (migration, then event auto-merge).
        when(plugin.getResource("config.conf")).thenAnswer(inv ->
                SpyglassConfigLoadMigrationTest.class.getResourceAsStream("/config.conf"));
        return plugin;
    }

    @Test
    void preVersioningConfigWithBothRemovedKnobsBootsWithItsSettings(@TempDir Path dataFolder)
            throws Exception {
        // A v1-era file (no config-version key): both later-removed knobs
        // set to their non-default values, plus settings the operator chose.
        Path file = dataFolder.resolve("config.conf");
        Files.writeString(file, "database { backend = \"sqlite\" }\n"
                + "storage {\n"
                + "  durability = \"wal-batched\"\n"
                + "  rolled-audit = \"receipts\"\n"
                + "  retention = \"8w\"\n"
                + "  queue-max = 250000\n"
                + "}\n"
                + "limits { max-radius = 250 }\n"
                + "server { name = \"lobby\" }\n");

        SpyglassConfig config = SpyglassConfig.load(pluginIn(dataFolder));

        // The operator's settings survived the migration.
        assertThat(config.database().backend()).isEqualTo(SpyglassConfig.Backend.SQLITE);
        assertThat(config.storage().retention().seconds())
                .isEqualTo(java.time.Duration.ofDays(7 * 8).toSeconds());
        assertThat(config.storage().queueMax()).isEqualTo(250_000);
        assertThat(config.limits().maxRadius()).isEqualTo(250);
        assertThat(config.server().name()).isEqualTo("lobby");

        // The rewritten file is stamped current with the dead keys dropped.
        ConfigurationNode written = HoconConfigurationLoader.builder().path(file).build().load();
        assertThat(written.node("config-version").getInt())
                .isEqualTo(SpyglassConfig.CONFIG_VERSION);
        assertThat(written.node("storage", "durability").virtual()).isTrue();
        assertThat(written.node("storage", "rolled-audit").virtual()).isTrue();

        // The pre-migration original is recoverable next to it.
        try (Stream<Path> siblings = Files.list(dataFolder)) {
            assertThat(siblings.filter(p -> p.getFileName().toString()
                    .startsWith("config.conf.v1.bak"))).hasSize(1);
        }
    }

    @Test
    void alreadyCurrentConfigLoadsWithoutRewriteOrBackup(@TempDir Path dataFolder)
            throws Exception {
        Path file = dataFolder.resolve("config.conf");
        Files.writeString(file, "config-version = " + SpyglassConfig.CONFIG_VERSION + "\n"
                + "database { backend = \"sqlite\" }\n"
                + "storage { retention = \"12w\" }\n");

        SpyglassConfig config = SpyglassConfig.load(pluginIn(dataFolder));

        assertThat(config.storage().retention().seconds())
                .isEqualTo(java.time.Duration.ofDays(7 * 12).toSeconds());
        try (Stream<Path> siblings = Files.list(dataFolder)) {
            assertThat(siblings.filter(p -> p.getFileName().toString().endsWith(".bak")))
                    .isEmpty();
        }
    }
}
