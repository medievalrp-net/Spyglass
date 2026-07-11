package net.medievalrp.spyglass.plugin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The operator who upgrades the jar and never reads a changelog. Every case
 * here is a hand-mangled config that must either boot with sane values or
 * fail loudly with the original file preserved - never silently reset, never
 * a stack trace pointing nowhere. All through the real
 * {@link SpyglassConfig#load} boot path.
 */
class LazyOperatorConfigTest {

    private static JavaPlugin pluginIn(Path dataFolder) {
        return pluginIn(dataFolder, Logger.getLogger("test"));
    }

    private static JavaPlugin pluginIn(Path dataFolder, Logger logger) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getResource("config.conf")).thenAnswer(inv ->
                LazyOperatorConfigTest.class.getResourceAsStream("/config.conf"));
        return plugin;
    }

    private static Path write(Path dataFolder, String content) throws IOException {
        Path file = dataFolder.resolve("config.conf");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void garbageValuesInRemovedKnobsDoNotBreakBoot(@TempDir Path dataFolder) throws Exception {
        // Nothing reads these keys anymore, so even values the old parser
        // would have thrown on must be inert.
        write(dataFolder, "storage {\n"
                + "  durability = \"wal-batchedd\"\n"
                + "  rolled-audit = 42\n"
                + "  retention = \"8w\"\n"
                + "}\n");

        SpyglassConfig config = SpyglassConfig.load(pluginIn(dataFolder));

        assertThat(config.storage().retention().seconds())
                .isEqualTo(java.time.Duration.ofDays(7 * 8).toSeconds());
    }

    @Test
    void handBrokenSyntaxFailsLoudAndPreservesTheFile(@TempDir Path dataFolder) throws Exception {
        String broken = "database {\n  backend = \"mongo\"\n"; // unclosed brace
        Path file = write(dataFolder, broken);

        assertThatThrownBy(() -> SpyglassConfig.load(pluginIn(dataFolder)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Fix the syntax");

        // The operator's file is untouched and a backup sits beside it.
        assertThat(Files.readString(file)).isEqualTo(broken);
        try (Stream<Path> siblings = Files.list(dataFolder)) {
            assertThat(siblings.filter(p -> p.getFileName().toString()
                    .startsWith("config.conf.corrupt.bak"))).hasSize(1);
        }
    }

    @Test
    void emptyFileLoadsDefaultsAndStampsTheVersion(@TempDir Path dataFolder) throws Exception {
        Path file = write(dataFolder, "");

        SpyglassConfig config = SpyglassConfig.load(pluginIn(dataFolder));

        assertThat(config.database().backend()).isEqualTo(SpyglassConfig.Backend.SQLITE);
        ConfigurationNode written = HoconConfigurationLoader.builder().path(file).build().load();
        assertThat(written.node("config-version").getInt())
                .isEqualTo(SpyglassConfig.CONFIG_VERSION);
    }

    @Test
    void handEditedFutureVersionSkipsMigrationAndStillBoots(@TempDir Path dataFolder)
            throws Exception {
        write(dataFolder, "config-version = 99\n"
                + "storage { durability = \"wal-batched\", retention = \"8w\" }\n");

        SpyglassConfig config = SpyglassConfig.load(pluginIn(dataFolder));

        assertThat(config.storage().retention().seconds())
                .isEqualTo(java.time.Duration.ofDays(7 * 8).toSeconds());
        // No migration ran, so no backup appeared.
        try (Stream<Path> siblings = Files.list(dataFolder)) {
            assertThat(siblings.filter(p -> p.getFileName().toString().contains(".bak")))
                    .isEmpty();
        }
    }

    @Test
    void handStampedCurrentVersionOnOldFormatFileWarnsAboutIgnoredKeys(@TempDir Path dataFolder)
            throws Exception {
        // The nastiest lazy edit: copying "config-version = 2" into an
        // old-format file suppresses migration, so the bare mongo keys are
        // silently dead. The load must say so.
        write(dataFolder, "config-version = " + SpyglassConfig.CONFIG_VERSION + "\n"
                + "database {\n"
                + "  backend = \"mongo\"\n"
                + "  uri = \"mongodb://ops.example:27017\"\n"
                + "}\n"
                + "storage { durability = \"wal-batched\" }\n");

        StringBuilder warnings = new StringBuilder();
        Logger logger = Logger.getLogger("lazy-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { warnings.append(r.getMessage()).append('\n'); }
            @Override public void flush() { }
            @Override public void close() { }
        });

        SpyglassConfig config = SpyglassConfig.load(pluginIn(dataFolder, logger));

        // Boots - but on the mongo defaults, and the log names the dead keys.
        assertThat(config.database().backend()).isEqualTo(SpyglassConfig.Backend.MONGO);
        assertThat(warnings.toString())
                .contains("IGNORED")
                .contains("database.uri")
                .contains("storage.durability");
    }

    @Test
    void nonNumericVersionReadsAsPreVersioningAndMigrates(@TempDir Path dataFolder)
            throws Exception {
        Path file = write(dataFolder, "config-version = \"latest\"\n"
                + "storage { rolled-audit = \"receipts\", retention = \"8w\" }\n");

        SpyglassConfig config = SpyglassConfig.load(pluginIn(dataFolder));

        assertThat(config.storage().retention().seconds())
                .isEqualTo(java.time.Duration.ofDays(7 * 8).toSeconds());
        ConfigurationNode written = HoconConfigurationLoader.builder().path(file).build().load();
        assertThat(written.node("config-version").getInt())
                .isEqualTo(SpyglassConfig.CONFIG_VERSION);
        assertThat(written.node("storage", "rolled-audit").virtual()).isTrue();
    }

    @Test
    void wrongTypedNumbersFallBackToDefaultsInsteadOfCrashing(@TempDir Path dataFolder)
            throws Exception {
        write(dataFolder, "storage { queue-max = \"half a million\" }\n"
                + "defaults { radius = \"big\" }\n");

        SpyglassConfig config = SpyglassConfig.load(pluginIn(dataFolder));

        assertThat(config.storage().queueMax()).isEqualTo(500_000);
        assertThat(config.defaults().radius()).isEqualTo(250);
    }

    @Test
    void unparseableRetentionDisablesLoudlyRatherThanGuessing(@TempDir Path dataFolder)
            throws Exception {
        // Deliberate hard-fail: retention drives deletion, and guessing a
        // default here could purge history the operator meant to keep
        // forever. The plugin disables with the cause instead.
        write(dataFolder, "storage { retention = \"banana\" }\n");

        assertThatThrownBy(() -> SpyglassConfig.load(pluginIn(dataFolder)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void removedLimitsKnobsAndInventedKeysAreHarmless(@TempDir Path dataFolder) throws Exception {
        write(dataFolder, "limits {\n"
                + "  rollback-result = 1000\n"
                + "  rollback-page-size = 50\n"
                + "  rollback-undo-cap = 10\n"
                + "  max-radius = 350\n"
                + "}\n"
                + "storage { chunk-loading = \"async\" }\n"
                + "my-own-block { note = \"do not delete\" }\n");

        SpyglassConfig config = SpyglassConfig.load(pluginIn(dataFolder));

        assertThat(config.limits().maxRadius()).isEqualTo(350);
    }

    @Test
    void eventEntryWithWrongShapeFallsBackToDefaults(@TempDir Path dataFolder) throws Exception {
        write(dataFolder, "events { break = \"yes\" }\n");

        SpyglassConfig config = SpyglassConfig.load(pluginIn(dataFolder));

        assertThat(config.enabled("break")).isTrue();
        assertThat(config.pastTense("break")).isEqualTo("break");
    }
}
