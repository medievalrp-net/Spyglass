package net.medievalrp.spyglass.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.file.Path;
import net.medievalrp.spyglass.plugin.storage.ClickHouseRecordStore;
import net.medievalrp.spyglass.plugin.storage.IndexManager;
import net.medievalrp.spyglass.plugin.storage.MariaDbRecordStore;
import net.medievalrp.spyglass.plugin.storage.MongoRecordStore;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import net.medievalrp.spyglass.plugin.storage.SqliteRecordStore;
import net.medievalrp.spyglass.proxy.command.BufferEvictionListener;
import net.medievalrp.spyglass.proxy.command.SpyglassCommand;
import net.medievalrp.spyglass.proxy.config.SpyglassProxyConfig;
import org.slf4j.Logger;

/**
 * Velocity-side companion to the Paper Spyglass plugin.
 *
 * <p>Reads the same Mongo / ClickHouse store the Paper plugins write to,
 * lets operators run cross-backend searches from the proxy, and slices
 * results by the {@code server} tag stamped at write-time. Read-only:
 * the proxy never records events (no world / block context to record),
 * never rolls back (rollback needs Bukkit), and never registers a wand.
 */
@Plugin(
        id = "spyglass",
        name = "Spyglass",
        version = "1.0.0",
        description = "Cross-server forensic log search for the Velocity proxy.",
        authors = {"medievalrp-net"})
public final class SpyglassProxy {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private SpyglassProxyConfig config;
    private RecordStore recordStore;

    @Inject
    public SpyglassProxy(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            this.config = SpyglassProxyConfig.load(dataDirectory);
        } catch (IOException ex) {
            logger.error("Failed to load Spyglass proxy config; plugin disabled.", ex);
            return;
        }

        try {
            this.recordStore = openStore(config, dataDirectory);
        } catch (RuntimeException ex) {
            logger.error("Failed to open Spyglass record store; plugin disabled.", ex);
            return;
        }
        logger.info("Spyglass: backend = {}", config.database().backend());

        // Register only /sgv on the proxy. Earlier revisions also bound
        // /spyglass and /sg, but Velocity dispatches its own commands
        // before forwarding to the backend, which silently shadowed the
        // Paper-side /spyglass (wand, rollback, etc.) the moment a player
        // joined a backend. /sgv is the cross-server-search namespace;
        // /spyglass on a backend keeps its full command tree.
        CommandManager cm = server.getCommandManager();
        CommandMeta meta = cm.metaBuilder("sgv")
                .plugin(this)
                .build();
        SpyglassCommand command = new SpyglassCommand(recordStore, config, logger);
        cm.register(meta, command);
        server.getEventManager().register(this, new BufferEvictionListener(command));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (recordStore != null) {
            try {
                recordStore.close();
            } catch (RuntimeException ex) {
                logger.warn("Spyglass record store close failed.", ex);
            }
        }
    }

    private static RecordStore openStore(SpyglassProxyConfig cfg, Path dataDirectory) {
        SpyglassProxyConfig.Database db = cfg.database();
        return switch (db.backend()) {
            case MONGO -> new MongoRecordStore(
                    // Read-only companion: skip collection-create, backfill, and
                    // index writes. The backend plugin owns schema and migrations.
                    db.uri(), db.name(), db.collection(), new IndexManager(), false);
            case CLICKHOUSE -> {
                SpyglassProxyConfig.ClickHouse ch = db.clickhouse();
                yield new ClickHouseRecordStore(
                        ch.host(), ch.port(), ch.database(), ch.table(),
                        ch.user(), ch.password(), ch.ssl());
            }
            case SQLITE -> {
                // Read-only: the embedded file must be reachable on the
                // proxy host (same machine / shared mount). A relative path
                // resolves under the proxy's data directory.
                Path configured = Path.of(db.sqlite().path());
                Path dbPath = configured.isAbsolute()
                        ? configured
                        : dataDirectory.resolve(configured);
                yield new SqliteRecordStore(dbPath, true);
            }
            case MARIADB -> {
                // Read-only companion: same network-served database the Paper
                // server writes to, opened read-only (no schema create / TTL).
                SpyglassProxyConfig.MariaDb maria = db.mariadb();
                yield new MariaDbRecordStore(
                        maria.host(), maria.port(), maria.database(),
                        maria.user(), maria.password(), maria.ssl(), true);
            }
        };
    }
}
