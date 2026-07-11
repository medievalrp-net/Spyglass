package net.medievalrp.spyglass.plugin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;

class ConfigMigratorTest {

    // The production remap table - drift here would mean testing a migration
    // the plugin never runs.
    private static final Map<String, String> REMAP = SpyglassConfig.MIGRATION_REMAP;

    @Test
    void movesMongoKeysUnderTheBlockAndRenamesName() throws SerializationException {
        ConfigurationNode user = BasicConfigurationNode.root();
        user.node("database", "backend").set("mongo");
        user.node("database", "uri").set("mongodb://db.internal:27017");
        user.node("database", "name").set("MyDb");
        user.node("database", "collection").set("Events");

        ConfigurationNode template = freshV2Template();
        ConfigMigrator.migrate(user, template, REMAP, 2);

        assertThat(template.node("database", "mongo", "uri").getString())
                .isEqualTo("mongodb://db.internal:27017");
        assertThat(template.node("database", "mongo", "database").getString()).isEqualTo("MyDb");
        assertThat(template.node("database", "mongo", "collection").getString()).isEqualTo("Events");
        // The operator's backend choice survives...
        assertThat(template.node("database", "backend").getString()).isEqualTo("mongo");
        // ...and the old bare keys are gone (the template never had them and the
        // migration relocated rather than re-adding them).
        assertThat(template.node("database", "uri").virtual()).isTrue();
        assertThat(template.node("database", "name").virtual()).isTrue();
        assertThat(template.node("config-version").getInt()).isEqualTo(2);
    }

    @Test
    void keepsOperatorValuesThatDifferFromNewDefaults() throws SerializationException {
        ConfigurationNode user = BasicConfigurationNode.root();
        user.node("limits", "max-radius").set(250);   // operator kept the pre-v2 value
        user.node("storage", "retention").set("8w");

        ConfigurationNode template = freshV2Template();
        template.node("limits", "max-radius").set(500);   // new shipped default
        template.node("storage", "retention").set("26w");

        ConfigMigrator.migrate(user, template, REMAP, 2);

        assertThat(template.node("limits", "max-radius").getInt()).isEqualTo(250);
        assertThat(template.node("storage", "retention").getString()).isEqualTo("8w");
    }

    @Test
    void newTemplateKeysStayAtTheirDefaultsWhenTheUserLacksThem() throws SerializationException {
        ConfigurationNode user = BasicConfigurationNode.root();
        user.node("server", "name").set("lobby");

        ConfigurationNode template = freshV2Template();
        template.node("metrics", "enabled").set(true);   // a key the old config never had

        ConfigMigrator.migrate(user, template, REMAP, 2);

        assertThat(template.node("server", "name").getString()).isEqualTo("lobby");
        assertThat(template.node("metrics", "enabled").getBoolean()).isTrue();
    }

    @Test
    void replacesListValuesWholesaleRatherThanIndexMerging() throws SerializationException {
        ConfigurationNode user = BasicConfigurationNode.root();
        user.node("events", "command", "redact").set(List.of("login", "reg"));

        ConfigurationNode template = freshV2Template();
        template.node("events", "command", "redact").set(List.of("a", "b", "c", "d"));

        ConfigMigrator.migrate(user, template, REMAP, 2);

        assertThat(template.node("events", "command", "redact").getList(String.class))
                .containsExactly("login", "reg");
    }

    @Test
    void absentVersionReadsAsOne() throws SerializationException {
        ConfigurationNode node = BasicConfigurationNode.root();
        assertThat(ConfigMigrator.readVersion(node)).isEqualTo(1);
        node.node("config-version").set(2);
        assertThat(ConfigMigrator.readVersion(node)).isEqualTo(2);
    }

    @Test
    void backupDoesNotClobberAnExistingOne(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("config.conf");
        Files.writeString(config, "config-version = 1\n");

        Path first = ConfigMigrator.backup(config, "v1");
        Files.writeString(config, "config-version = 1\nedited = true\n");
        Path second = ConfigMigrator.backup(config, "v1");

        assertThat(first).isNotEqualTo(second);
        assertThat(Files.exists(first)).isTrue();
        assertThat(Files.exists(second)).isTrue();
        // The first backup still holds the original, untouched.
        assertThat(Files.readString(first)).isEqualTo("config-version = 1\n");
    }

    @Test
    void endToEndAgainstRealTemplateKeepsValuesAndComments(@TempDir Path dir) throws Exception {
        // Load the actual shipped template (a main resource on the test classpath).
        ConfigurationNode template;
        try (java.io.InputStream in = ConfigMigratorTest.class.getResourceAsStream("/config.conf")) {
            assertThat(in).as("bundled config.conf on classpath").isNotNull();
            String templateText = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            template = HoconConfigurationLoader.builder()
                    .source(() -> new java.io.BufferedReader(new java.io.StringReader(templateText)))
                    .build().load();
        }

        // A pre-v2 on-disk config: bare mongo keys, a value the operator kept,
        // and the durability knob v3 removed (#307).
        Path file = dir.resolve("config.conf");
        Files.writeString(file, "database {\n"
                + "  backend = \"mongo\"\n"
                + "  uri = \"mongodb://ops.example:27017\"\n"
                + "  name = \"ProdDb\"\n"
                + "  collection = \"Events\"\n"
                + "}\n"
                + "storage { durability = \"wal-batched\" }\n"
                + "limits { max-radius = 250 }\n");
        HoconConfigurationLoader loader = HoconConfigurationLoader.builder().path(file).build();
        ConfigurationNode user = loader.load();

        assertThat(ConfigMigrator.readVersion(user)).isEqualTo(1);
        ConfigMigrator.migrate(user, template, REMAP, SpyglassConfig.CONFIG_VERSION);
        loader.save(template);

        // Re-parse the written file: structure and values are correct.
        ConfigurationNode result = HoconConfigurationLoader.builder().path(file).build().load();
        assertThat(result.node("database", "mongo", "uri").getString()).isEqualTo("mongodb://ops.example:27017");
        assertThat(result.node("database", "mongo", "database").getString()).isEqualTo("ProdDb");
        assertThat(result.node("database", "mongo", "collection").getString()).isEqualTo("Events");
        // The v2 discrete fields arrive from the template at their defaults; the
        // migrated connection string lands in the override, so a URI-configured
        // operator keeps working untouched.
        assertThat(result.node("database", "mongo", "host").getString()).isEqualTo("localhost");
        assertThat(result.node("database", "mongo", "port").getInt()).isEqualTo(27017);
        assertThat(result.node("database", "uri").virtual()).isTrue();            // bare key relocated
        assertThat(result.node("database", "backend").getString()).isEqualTo("mongo");
        assertThat(result.node("limits", "max-radius").getInt()).isEqualTo(250);  // kept, not reset to 500
        // The removed durability knob was dropped, not carried into the new file.
        assertThat(result.node("storage", "durability").virtual()).isTrue();
        assertThat(result.node("config-version").getInt()).isEqualTo(SpyglassConfig.CONFIG_VERSION);

        // The template's comments made it into the upgraded file - the reason we
        // rebuild from the template rather than editing the old file in place.
        String written = Files.readString(file);
        assertThat(written).contains("Storage backend");
        assertThat(written).contains("recommended for large servers");
    }

    // The upgrade the #307/#312 removals lean on: a 1.0.8 operator who opted
    // into wal-batched durability and receipts rolled-audit (both removed)
    // boots the new jar with every other setting intact and the dead keys
    // gone. Before this, a leftover unrecognized durability value would have
    // hard-disabled the plugin at config load.
    @Test
    void unversionedConfigWithBothRemovedKnobsMigratesCleanly() throws SerializationException {
        ConfigurationNode user = BasicConfigurationNode.root();
        user.node("storage", "durability").set("wal-batched");
        user.node("storage", "rolled-audit").set("receipts");
        user.node("storage", "retention").set("8w");
        user.node("storage", "queue-max").set(250_000);
        user.node("database", "backend").set("clickhouse");

        ConfigurationNode template = freshV2Template();
        template.node("storage", "retention").set("26w");
        template.node("storage", "queue-max").set(500_000);
        ConfigMigrator.migrate(user, template, REMAP, SpyglassConfig.CONFIG_VERSION);

        assertThat(template.node("storage", "durability").virtual()).isTrue();
        assertThat(template.node("storage", "rolled-audit").virtual()).isTrue();
        assertThat(template.node("storage", "retention").getString()).isEqualTo("8w");
        assertThat(template.node("storage", "queue-max").getInt()).isEqualTo(250_000);
        assertThat(template.node("database", "backend").getString()).isEqualTo("clickhouse");
        assertThat(template.node("config-version").getInt())
                .isEqualTo(SpyglassConfig.CONFIG_VERSION);
    }

    // A half-updated file (the operator pasted the new mongo block but left
    // the old bare keys) must resolve to the explicit new-format value no
    // matter which block comes first - not to whichever the overlay happens
    // to visit last.
    @Test
    void halfUpdatedConfigKeepsTheExplicitNewFormatValueInEitherOrder() throws Exception {
        String bareFirst = "database {\n"
                + "  uri = \"mongodb://old.example:27017\"\n"
                + "  mongo { uri = \"mongodb://new.example:27017\" }\n"
                + "}\n";
        String blockFirst = "database {\n"
                + "  mongo { uri = \"mongodb://new.example:27017\" }\n"
                + "  uri = \"mongodb://old.example:27017\"\n"
                + "}\n";
        for (String content : List.of(bareFirst, blockFirst)) {
            ConfigurationNode user = HoconConfigurationLoader.builder()
                    .source(() -> new java.io.BufferedReader(new java.io.StringReader(content)))
                    .build().load();
            ConfigurationNode template = freshV2Template();
            ConfigMigrator.migrate(user, template, REMAP, SpyglassConfig.CONFIG_VERSION);
            assertThat(template.node("database", "mongo", "uri").getString())
                    .as("explicit mongo.uri wins over the relocated bare uri")
                    .isEqualTo("mongodb://new.example:27017");
        }
    }

    private static ConfigurationNode freshV2Template() throws SerializationException {
        ConfigurationNode template = BasicConfigurationNode.root();
        template.node("config-version").set(2);
        template.node("database", "backend").set("sqlite");
        template.node("database", "mongo", "uri").set("mongodb://localhost:27017");
        template.node("database", "mongo", "database").set("Spyglass");
        template.node("database", "mongo", "collection").set("EventRecords");
        return template;
    }
}
