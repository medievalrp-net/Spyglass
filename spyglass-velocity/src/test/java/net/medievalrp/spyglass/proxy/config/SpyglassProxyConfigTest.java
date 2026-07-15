package net.medievalrp.spyglass.proxy.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpyglassProxyConfigTest {

    @Test
    void legacyTopLevelMongoKeysStillWorkWithoutAMongoBlock(@TempDir Path dir) throws Exception {
        // A proxy config written against the pre-mongo-block format keeps
        // connecting exactly as before.
        Files.writeString(dir.resolve("config.conf"), "database {\n"
                + "  backend = \"mongo\"\n"
                + "  uri = \"mongodb://ops.example:27017\"\n"
                + "  name = \"ProdDb\"\n"
                + "  collection = \"Events\"\n"
                + "}\n");

        SpyglassProxyConfig config = SpyglassProxyConfig.load(dir);

        assertThat(config.database().mongo().uri()).isEqualTo("mongodb://ops.example:27017");
        assertThat(config.database().mongo().database()).isEqualTo("ProdDb");
        assertThat(config.database().mongo().collection()).isEqualTo("Events");
    }

    @Test
    void mongoBlockIsAuthoritativeOverStaleTopLevelKeys(@TempDir Path dir) throws Exception {
        // The half-updated hand-edit: the operator adopted the mongo { }
        // block but left the old top-level lines (the old default file
        // shipped an active uri = "mongodb://localhost:27017"). The block
        // must win - a stale uri may not silently repoint the proxy.
        Files.writeString(dir.resolve("config.conf"), "database {\n"
                + "  backend = \"mongo\"\n"
                + "  uri = \"mongodb://stale.example:27017\"\n"
                + "  name = \"StaleDb\"\n"
                + "  mongo {\n"
                + "    host = \"dbhost\"\n"
                + "    database = \"ProxyDb\"\n"
                + "  }\n"
                + "}\n");

        SpyglassProxyConfig config = SpyglassProxyConfig.load(dir);

        assertThat(config.database().mongo().uri()).isEqualTo("mongodb://dbhost:27017");
        assertThat(config.database().mongo().database()).isEqualTo("ProxyDb");
        // The block omits collection, so the default applies - not StaleDb's
        // sibling top-level value.
        assertThat(config.database().mongo().collection()).isEqualTo("EventRecords");
    }
}
