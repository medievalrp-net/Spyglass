package net.medievalrp.spyglass.plugin.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportConfigTest {

    @Test
    void loadsNamedSources(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("import.conf");
        Files.writeString(file, """
            sources {
              old-survival {
                host = "10.0.0.5"
                port = 3307
                database = "coreprotect"
                user = "reader"
                password = "secret"
                server-name = "survival"
              }
            }
            """);
        ImportConfig config = ImportConfig.loadFrom(file);
        assertThat(config.source("old-survival")).isPresent();
        ImportConfig.MysqlSourceSpec s = config.source("old-survival").orElseThrow();
        assertThat(s.host()).isEqualTo("10.0.0.5");
        assertThat(s.port()).isEqualTo(3307);
        assertThat(s.database()).isEqualTo("coreprotect");
        assertThat(s.user()).isEqualTo("reader");
        assertThat(s.password()).isEqualTo("secret");
        assertThat(s.serverName()).isEqualTo("survival");
    }

    @Test
    void missingFileYieldsEmptyConfig(@TempDir Path dir) throws Exception {
        ImportConfig config = ImportConfig.loadFrom(dir.resolve("nope.conf"));
        assertThat(config.sources()).isEmpty();
        assertThat(config.source("anything")).isEmpty();
    }

    @Test
    void portDefaultsTo3306WhenOmitted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("import.conf");
        Files.writeString(file, """
            sources { s { host = "h", database = "d", user = "u", password = "p" } }
            """);
        assertThat(config(file).source("s").orElseThrow().port()).isEqualTo(3306);
    }

    @Test
    void mysqlSourceSpecToStringRedactsPassword() {
        ImportConfig.MysqlSourceSpec spec =
                new ImportConfig.MysqlSourceSpec("h", 3306, "db", "user", "s3cret", "srv");
        assertThat(spec.toString()).doesNotContain("s3cret");
        assertThat(spec.toString()).contains("host=h").contains("user=user");
    }

    private static ImportConfig config(Path f) throws Exception { return ImportConfig.loadFrom(f); }
}
