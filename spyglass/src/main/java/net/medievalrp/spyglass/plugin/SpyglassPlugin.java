package net.medievalrp.spyglass.plugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.SpyglassLimits;
import net.medievalrp.spyglass.api.event.RecordCommittedEvent;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.api.SpyglassApiImpl;
import net.medievalrp.spyglass.plugin.command.SpyglassCommands;
import net.medievalrp.spyglass.plugin.command.service.StatsService;
import net.medievalrp.spyglass.plugin.pipeline.IngestStats;
import net.medievalrp.spyglass.plugin.pipeline.IngestStatsReporter;
import net.medievalrp.spyglass.plugin.command.SpyglassSuggestions;
import net.medievalrp.spyglass.plugin.command.PageCache;
import net.medievalrp.spyglass.plugin.command.param.BlockParam;
import net.medievalrp.spyglass.plugin.command.param.CauseParam;
import net.medievalrp.spyglass.plugin.command.param.ChunkRadiusParam;
import net.medievalrp.spyglass.plugin.command.param.CustomItemParam;
import net.medievalrp.spyglass.plugin.command.param.EnchantParam;
import net.medievalrp.spyglass.plugin.command.param.EntityParam;
import net.medievalrp.spyglass.plugin.command.param.EventParam;
import net.medievalrp.spyglass.plugin.command.param.IpParam;
import net.medievalrp.spyglass.plugin.command.param.ItemLoreParam;
import net.medievalrp.spyglass.plugin.command.param.ItemMaterialParam;
import net.medievalrp.spyglass.plugin.command.param.ItemNameParam;
import net.medievalrp.spyglass.plugin.command.param.ItemTagParam;
import net.medievalrp.spyglass.plugin.command.param.ContentParam;
import net.medievalrp.spyglass.plugin.command.param.MessageParam;
import net.medievalrp.spyglass.plugin.command.param.PlayerParam;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import net.medievalrp.spyglass.plugin.command.param.RadiusParam;
import net.medievalrp.spyglass.plugin.command.param.RecipientParam;
import net.medievalrp.spyglass.plugin.command.param.ServerParam;
import net.medievalrp.spyglass.plugin.command.param.TargetParam;
import net.medievalrp.spyglass.plugin.command.param.BeforeParam;
import net.medievalrp.spyglass.plugin.command.param.TimeParam;
import net.medievalrp.spyglass.plugin.command.param.WorldParam;
import net.medievalrp.spyglass.plugin.command.render.ResultRenderer;
import net.medievalrp.spyglass.plugin.command.service.HelpService;
import net.medievalrp.spyglass.plugin.command.service.RollbackService;
import net.medievalrp.spyglass.plugin.command.service.SearchService;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import net.medievalrp.spyglass.plugin.command.service.TeleportService;
import net.medievalrp.spyglass.plugin.command.service.ToolService;
import net.medievalrp.spyglass.plugin.command.service.UndoService;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.block.BlockBreakListener;
import net.medievalrp.spyglass.plugin.listener.block.BlockMultiPlaceListener;
import net.medievalrp.spyglass.plugin.listener.block.BlockPlaceListener;
import net.medievalrp.spyglass.plugin.listener.block.BucketListener;
import net.medievalrp.spyglass.plugin.listener.block.ContainerDropListener;
import net.medievalrp.spyglass.plugin.listener.block.DependantBreakListener;
import net.medievalrp.spyglass.plugin.listener.block.FallingBlockLandListener;
import net.medievalrp.spyglass.plugin.listener.block.MultiBlockBreakListener;
import net.medievalrp.spyglass.plugin.listener.chat.ChatListener;
import net.medievalrp.spyglass.plugin.listener.chat.CommandListener;
import net.medievalrp.spyglass.plugin.listener.chat.CommandRedaction;
import net.medievalrp.spyglass.plugin.listener.container.ContainerDragListener;
import net.medievalrp.spyglass.plugin.listener.container.ContainerInteractListener;
import net.medievalrp.spyglass.plugin.listener.container.ContainerTransactionListener;
import net.medievalrp.spyglass.plugin.listener.entity.ArmorStandManipulateListener;
import net.medievalrp.spyglass.plugin.listener.entity.EntityDamageListener;
import net.medievalrp.spyglass.plugin.listener.entity.EntityDeathListener;
import net.medievalrp.spyglass.plugin.listener.entity.EntityDismountListener;
import net.medievalrp.spyglass.plugin.listener.entity.EntityDoorBreakListener;
import net.medievalrp.spyglass.plugin.listener.entity.EntityMountListener;
import net.medievalrp.spyglass.plugin.listener.entity.EntityNamingListener;
import net.medievalrp.spyglass.plugin.listener.entity.ItemFrameInteractListener;
import net.medievalrp.spyglass.plugin.listener.environment.BlockBurnListener;
import net.medievalrp.spyglass.plugin.listener.environment.BlockExplodeListener;
import net.medievalrp.spyglass.plugin.listener.environment.BlockFadeListener;
import net.medievalrp.spyglass.plugin.listener.environment.BlockFormListener;
import net.medievalrp.spyglass.plugin.listener.environment.BlockGrowListener;
import net.medievalrp.spyglass.plugin.listener.environment.BlockIgniteListener;
import net.medievalrp.spyglass.plugin.listener.environment.EntityExplodeListener;
import net.medievalrp.spyglass.plugin.listener.environment.LeavesDecayListener;
import net.medievalrp.spyglass.plugin.listener.environment.StructureGrowListener;
import net.medievalrp.spyglass.plugin.listener.item.CreativeCloneListener;
import net.medievalrp.spyglass.plugin.listener.item.HopperTransferListener;
import net.medievalrp.spyglass.plugin.listener.item.ItemDropListener;
import net.medievalrp.spyglass.plugin.listener.item.ItemPickupListener;
import net.medievalrp.spyglass.plugin.listener.item.TransferDedup;
import net.medievalrp.spyglass.plugin.listener.modern.BookshelfListener;
import net.medievalrp.spyglass.plugin.listener.modern.BrushListener;
import net.medievalrp.spyglass.plugin.listener.modern.BundleTransactionListener;
import net.medievalrp.spyglass.plugin.listener.modern.CraftBookSignListener;
import net.medievalrp.spyglass.plugin.listener.modern.CrafterListener;
import net.medievalrp.spyglass.plugin.listener.modern.DecoratedPotListener;
import net.medievalrp.spyglass.plugin.listener.modern.DelayedInteractionTracker;
import net.medievalrp.spyglass.plugin.listener.modern.SculkListener;
import net.medievalrp.spyglass.plugin.listener.modern.ShulkerTransactionListener;
import net.medievalrp.spyglass.plugin.listener.modern.VaultListener;
import net.medievalrp.spyglass.plugin.listener.player.BlockUseListener;
import net.medievalrp.spyglass.plugin.listener.player.JoinListener;
import net.medievalrp.spyglass.plugin.listener.player.QuitListener;
import net.medievalrp.spyglass.plugin.listener.player.TeleportListener;
import net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder;
import net.medievalrp.spyglass.plugin.pipeline.DeferredSerializer;
import net.medievalrp.spyglass.plugin.pipeline.RecordCommittedPublisher;
import net.medievalrp.spyglass.plugin.rollback.ClickHouseUndoStack;
import net.medievalrp.spyglass.plugin.rollback.MariaDbUndoStack;
import net.medievalrp.spyglass.plugin.rollback.MongoUndoStack;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.RollbackPhysicsBlocker;
import net.medievalrp.spyglass.plugin.rollback.SqliteUndoStack;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import net.medievalrp.spyglass.plugin.storage.ClickHouseRecordStore;
import net.medievalrp.spyglass.plugin.storage.IndexManager;
import net.medievalrp.spyglass.plugin.storage.MariaDbRecordStore;
import net.medievalrp.spyglass.plugin.storage.MongoRecordStore;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import net.medievalrp.spyglass.plugin.storage.SqliteRecordStore;
import net.medievalrp.spyglass.plugin.storage.SynthesizingRecordStore;
import net.medievalrp.spyglass.plugin.salvage.ClickHouseSalvageStore;
import net.medievalrp.spyglass.plugin.salvage.MariaDbSalvageStore;
import net.medievalrp.spyglass.plugin.salvage.MongoSalvageStore;
import net.medievalrp.spyglass.plugin.salvage.SqliteSalvageStore;
import net.medievalrp.spyglass.plugin.salvage.SalvageCapturer;
import net.medievalrp.spyglass.plugin.salvage.SalvageStore;
import net.medievalrp.spyglass.plugin.salvage.SalvageView;
import net.medievalrp.spyglass.plugin.salvage.SalvageViews;
import net.medievalrp.spyglass.plugin.salvage.SalvageWithdrawLogger;
import net.medievalrp.spyglass.plugin.salvage.SalvageWithdrawals;
import net.medievalrp.spyglass.plugin.command.service.SalvageService;
import net.medievalrp.spyglass.plugin.command.service.tool.ClickHouseToolStateStore;
import net.medievalrp.spyglass.plugin.command.service.tool.MariaDbToolStateStore;
import net.medievalrp.spyglass.plugin.command.service.tool.MongoToolStateStore;
import net.medievalrp.spyglass.plugin.command.service.tool.SqliteToolStateStore;
import net.medievalrp.spyglass.plugin.command.service.tool.ToolStateStore;
import net.medievalrp.spyglass.plugin.command.service.tool.WandInteractListener;
import net.medievalrp.spyglass.plugin.util.FallingBlockTracker;
import net.medievalrp.spyglass.plugin.worldedit.WorldEditLifecycleListener;
import net.medievalrp.spyglass.plugin.worldedit.WorldEditSubscriber;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpyglassPlugin extends JavaPlugin {

    /**
     * bStats service id for Spyglass, assigned at https://bstats.org when the
     * plugin was registered. Submission is gated on {@code metrics.enabled} in
     * config.conf (and on the server-wide bStats opt-out the library writes).
     */
    private static final int BSTATS_PLUGIN_ID = 32119;

    private AsyncRecorder recorder;
    private RecordStore recordStore;
    /**
     * Worker for the off-main-thread palette-write phase of the
     * rollback engine. Held as a field so {@link #onDisable} can
     * shutdown the thread cleanly on plugin unload — without this,
     * the daemon worker leaks across {@code /reload} (it dies on
     * JVM exit but hangs around for the lifetime of the running
     * server otherwise).
     */
    private java.util.concurrent.ExecutorService worldWriteExecutor;
    /**
     * Repeating async task that sweeps expired entries out of
     * {@link net.medievalrp.spyglass.plugin.util.FallingBlockTracker}.
     * Without this, cascade cells whose falling-block entities never
     * cleanly land (shatter in water/lava, void, /kill, anti-cheat
     * despawn) accumulate forever -- #128.
     */
    private org.bukkit.scheduler.BukkitTask fallingBlockPurgeTask;
    // #168: repeating async task that logs the ingest analytics report. Null
    // unless analytics.enabled; cancelled in onDisable.
    private org.bukkit.scheduler.BukkitTask analyticsTask;
    private UndoStack undoStack;
    private ToolStateStore toolStateStore;
    private SalvageStore salvageStore;
    private Executor queryExecutor;
    /**
     * Off-main serialization stage for the item-heavy listeners. Item
     * serialization (NBT to bytes to base64) is the bulk of their
     * main-thread cost; pickups (#97/#103) and container transactions
     * (#98) snapshot on the main thread and hand the heavy work here.
     * For the rollbackable container records it also gates the recorder's
     * flush (read-your-writes). Drained before the recorder on shutdown so
     * nothing in flight is lost (see onDisable).
     */
    private DeferredSerializer deferredSerializer;
    private SpyglassConfig config;
    private Metrics metrics;
    private WorldEditSubscriber worldEditSubscriber;
    private WorldEditLifecycleListener worldEditLifecycle;

    @Override
    public void onEnable() {
        try {
            config = SpyglassConfig.load(this);
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Failed to load config. Disabling the plugin.", ex);
            setEnabled(false);
            return;
        }

        // Per-event-type retention (#181): global storage.retention as the
        // default, with per-event overrides. Drives every backend's expiry.
        net.medievalrp.spyglass.plugin.storage.RetentionPolicy retentionPolicy =
                config.retentionPolicy();
        try {
            switch (config.database().backend()) {
                case MONGO -> {
                    SpyglassConfig.Database db = config.database();
                    MongoRecordStore mongoStore = new MongoRecordStore(
                            db.mongo().uri(), db.mongo().database(), db.mongo().collection(),
                            new IndexManager(), retentionPolicy);
                    recordStore = mongoStore;
                    undoStack = new MongoUndoStack(
                            mongoStore.database(), mongoStore.codecRegistry());
                    toolStateStore = new MongoToolStateStore(
                            mongoStore.database(), getLogger());
                    salvageStore = new MongoSalvageStore(
                            mongoStore.database(), mongoStore.codecRegistry(), 30L);
                }
                case CLICKHOUSE -> {
                    SpyglassConfig.ClickHouse ch = config.database().clickhouse();
                    ClickHouseRecordStore chStore = new ClickHouseRecordStore(
                            ch.host(), ch.port(), ch.database(), ch.table(),
                            ch.user(), ch.password(), ch.ssl(), retentionPolicy);
                    recordStore = chStore;
                    undoStack = new ClickHouseUndoStack(
                            chStore.client(), ch.database());
                    toolStateStore = new ClickHouseToolStateStore(
                            chStore.client(), ch.database());
                    salvageStore = new ClickHouseSalvageStore(
                            chStore.client(), ch.database(), 30L);
                }
                case SQLITE -> {
                    // One embedded file holds every store. A relative path
                    // resolves under the plugin data folder.
                    java.nio.file.Path configured =
                            java.nio.file.Path.of(config.database().sqlite().path());
                    java.nio.file.Path dbPath = configured.isAbsolute()
                            ? configured
                            : getDataFolder().toPath().resolve(configured);
                    // Retention drives the SQLite TTL sweep and the reconstructed
                    // expiry on column-stored rows (which don't carry an
                    // expires_at column).
                    SqliteRecordStore sqliteStore = new SqliteRecordStore(
                            dbPath, false, retentionPolicy);
                    recordStore = sqliteStore;
                    undoStack = new SqliteUndoStack(sqliteStore);
                    toolStateStore = new SqliteToolStateStore(sqliteStore);
                    salvageStore = new SqliteSalvageStore(sqliteStore, 30L);
                }
                case MARIADB -> {
                    // Client-server SQL backend: records, undo ledger, wand
                    // state, and salvage all live in the one MariaDB / MySQL
                    // database. Retention drives the periodic TTL sweep and the
                    // reconstructed expiry on column-stored rows.
                    SpyglassConfig.MariaDb maria = config.database().mariadb();
                    MariaDbRecordStore mariaStore = new MariaDbRecordStore(
                            maria.host(), maria.port(), maria.database(),
                            maria.user(), maria.password(), maria.ssl(),
                            retentionPolicy);
                    recordStore = mariaStore;
                    undoStack = new MariaDbUndoStack(mariaStore);
                    toolStateStore = new MariaDbToolStateStore(mariaStore);
                    salvageStore = new MariaDbSalvageStore(mariaStore, 30L);
                }
            }
            // #22: searches synthesize per-block rolled-* entries
            // from rollback-op records instead of reading persisted
            // receipts. Wrapping here puts every read path - plugin
            // search, the public API, IP resolution - behind the
            // same merge; writes and the rollback's streaming page
            // reads delegate untouched.
            recordStore = new SynthesizingRecordStore(recordStore, true,
                    net.medievalrp.spyglass.plugin.rollback.RollbackEngine
                            .containerMaterialNames());
            getLogger().info("Spyglass: backend = " + config.database().backend());
        } catch (Exception ex) {
            getLogger().severe("Failed to initialize record store ("
                    + config.database().backend() + "): " + ex.getMessage());
            setEnabled(false);
            return;
        }

        queryExecutor = Executors.newVirtualThreadPerTaskExecutor();
        // One off-thread serialization stage for the item-heavy listeners
        // (pickups #97/#103, container transactions #98). It guards poison
        // items (logs + skips, never stderr), runs the heavy work on
        // virtual threads, and tracks in-flight tasks so the recorder's
        // flush can drain it before a rollback reads.
        deferredSerializer = new DeferredSerializer(getLogger());

        // On-disk overflow for the uncappable bulk-edit firehose: when the
        // queue is at its ceiling, a vanilla-WorldEdit paste spills here
        // instead of growing the heap. Only meaningful with a ceiling set.
        boolean spillEnabled = config.storage().spillToDisk()
                && config.storage().queueMax() > 0;
        net.medievalrp.spyglass.plugin.pipeline.SpillBuffer spill =
                new net.medievalrp.spyglass.plugin.pipeline.SpillBuffer(
                        getDataFolder().toPath(), spillEnabled, getLogger());
        if (spillEnabled) {
            getLogger().info("Spyglass overflow spill: enabled (bulk-edit overflow spills to disk "
                    + "at the queue-max ceiling).");
        }

        recorder = new AsyncRecorder(
                config.storage().queueCapacity(), config.storage().queueMax(),
                recordStore, spill, Bukkit::isPrimaryThread, getLogger());
        // #180: cap how fast the drain reclaims a large on-disk spill backlog in
        // the background, so it never saturates the store on a live server.
        recorder.setSpillDrainRate(config.storage().spillDrainRate());
        // Publish RecordCommittedEvent to Bukkit listeners on every
        // intake. Done via a hook (rather than a direct Bukkit call
        // inside AsyncRecorder) so the recorder stays unit-testable
        // headless. The publisher skips the allocation + dispatch
        // entirely when nothing is listening — the hook runs on the
        // recording (main) thread, so an unconsumed dispatch is pure
        // per-record waste on the ingest hot path of every event type.
        recorder.onCommitted(new RecordCommittedPublisher(
                () -> RecordCommittedEvent.getHandlerList().getRegisteredListeners().length,
                record -> Bukkit.getPluginManager().callEvent(new RecordCommittedEvent(record))));
        // #98: rollbackable container records serialize off-thread through
        // deferredSerializer before reaching the queue. A rollback flushes
        // the recorder for read-your-writes, so make flush() drain that
        // stage first — otherwise a rollback right after a deposit burst
        // would snapshot the queue before the in-flight records land.
        recorder.setFlushBarrier(deferredSerializer::awaitQuiescence);

        // One-release upgrade shim (#307): the removed wal-batched durability
        // mode may have left fsynced-but-unacked batch files behind after an
        // unclean exit. Replay them before the listeners come online, so
        // recovered records land in the DB before new ones start flowing.
        net.medievalrp.spyglass.plugin.pipeline.WalPendingReplay.replay(
                getDataFolder().toPath(), recorder::record, getLogger());

        // #168: opt-in ingest analytics. Created AFTER the WAL upgrade replay
        // (so recovered records aren't counted as live ingest) and BEFORE listeners come
        // online (so every live event is tallied). Null - and zero hot-path
        // overhead - when analytics.enabled = false.
        IngestStats ingestStats = null;
        if (config.analytics().enabled()) {
            ingestStats = new IngestStats(
                    recorder::queueDepth, recorder::drainedCount, recorder::droppedCount);
            recorder.setIngestStats(ingestStats);
        }

        // Mutable, thread-safe: SpyglassApi#registerEvent adds custom event
        // names at runtime, and the same instance is shared with EventParam
        // so a:<name> parses for them. Seeded from the enabled config events.
        Set<String> enabledEvents = java.util.concurrent.ConcurrentHashMap.newKeySet();
        config.events().entrySet().stream()
                .filter(entry -> entry.getValue().enabled())
                .map(Map.Entry::getKey)
                .forEach(enabledEvents::add);

        // Bind the per-server id stream before any listener can mint a
        // record id (#44): instance bits keep sequences collision-free
        // when multiple backends share one store.
        net.medievalrp.spyglass.api.util.EventIds.bindInstance(config.server().name().hashCode());
        RecordingSupport support = new RecordingSupport(config.storage().retention(), config.server().name());
        DelayedInteractionTracker delayedTracker = new DelayedInteractionTracker(this);
        // #226: shared between the hopper-transfer listener and the purge timer
        // below. Collapses repeating automated hopper flow so a farm line does
        // not flood the store; holds no Bukkit state.
        TransferDedup transferDedup = new TransferDedup();

        // Every recording listener in one list. `events()` declares the event
        // names each emits; we register with Bukkit only when at least one is
        // enabled in config.
        List<RecordingListener> listeners = List.of(
                new BlockBreakListener(recorder, support, deferredSerializer),
                new MultiBlockBreakListener(recorder, support),
                new DependantBreakListener(recorder, support),
                new BlockExplodeListener(recorder, support, this, deferredSerializer),
                new EntityExplodeListener(recorder, support, deferredSerializer),
                new BlockBurnListener(recorder, support, this),
                new BlockPlaceListener(recorder, support, deferredSerializer),
                new BlockMultiPlaceListener(recorder, support),
                // Bucket-placed / -removed fluid (#228). Reuses the block
                // place/break records; per-event gated on enabledEvents like the
                // hopper listener since one listener carries both toggles.
                new BucketListener(recorder, support, deferredSerializer, enabledEvents),
                new FallingBlockLandListener(recorder, support),
                new ContainerTransactionListener(recorder, support, deferredSerializer,
                        task -> getServer().getScheduler().runTask(this, task)),
                new ContainerDragListener(recorder, support),
                new ContainerInteractListener(recorder, support),
                new BlockUseListener(recorder, support),
                new ContainerDropListener(recorder, support),
                new ChatListener(recorder, support),
                new CommandListener(recorder, support,
                        new CommandRedaction(config.commandRedact())),
                new JoinListener(recorder, support),
                new QuitListener(recorder, support),
                new LeavesDecayListener(recorder, support),
                new BlockFadeListener(recorder, support),
                new BlockFormListener(recorder, support),
                new BlockGrowListener(recorder, support),
                new StructureGrowListener(recorder, support),
                new BlockIgniteListener(recorder, support, this),
                new ItemDropListener(recorder, support),
                new ItemPickupListener(recorder, support, deferredSerializer),
                new HopperTransferListener(recorder, support, deferredSerializer,
                        enabledEvents, transferDedup),
                new CreativeCloneListener(recorder, support),
                new TeleportListener(recorder, support),
                new EntityDeathListener(recorder, support, enabledEvents, deferredSerializer),
                new EntityDamageListener(recorder, support),
                new EntityMountListener(recorder, support),
                new EntityDismountListener(recorder, support),
                new ArmorStandManipulateListener(recorder, support),
                new ItemFrameInteractListener(recorder, support),
                new EntityNamingListener(recorder, support),
                new EntityDoorBreakListener(recorder, support),
                new BookshelfListener(recorder, support),
                new DecoratedPotListener(recorder, support),
                new ShulkerTransactionListener(recorder, support,
                        task -> getServer().getScheduler().runTask(this, task)),
                new BundleTransactionListener(recorder, support, this),
                new CrafterListener(recorder, support, deferredSerializer),
                new SculkListener(recorder, support),
                new BrushListener(recorder, support, delayedTracker),
                new VaultListener(recorder, support, delayedTracker));
        for (RecordingListener listener : listeners) {
            if (listener.events().stream().anyMatch(enabledEvents::contains)) {
                getServer().getPluginManager().registerEvents(listener, this);
            }
        }
        // CraftBook sign-use is only registered when CraftBook is live
        // on the server — a vanilla deployment doesn't need PlayerInteract
        // fired against every sign-right-click for a feature no one's
        // using.
        if (CraftBookSignListener.isCraftBookEnabled() && enabledEvents.contains("useSign")) {
            getServer().getPluginManager().registerEvents(
                    new CraftBookSignListener(recorder, support), this);
            getLogger().info("Spyglass: CraftBook detected, useSign logging enabled.");
        }

        SpyglassLimits apiLimits = new SpyglassLimits(
                config.limits().maxRadius(),
                config.defaults().radius(),
                config.defaults().time(),
                config.storage().retention());
        SpyglassApiImpl apiImpl = new SpyglassApiImpl(
                recorder, recordStore, queryExecutor, enabledEvents, apiLimits,
                config.server().name(), getLogger());
        // Store-backed name fallback: resolves players the Bukkit cache never
        // saw (imported histories, shared stores) into playerId predicates so
        // rollback-by-name works on the SQLite/MariaDB lean readers.
        apiImpl.registerQueryParamHandler(
                PlayerParam.withStoreFallback(recordStore::resolvePlayerId));
        apiImpl.registerQueryParamHandler(new EventParam(enabledEvents));
        apiImpl.registerQueryParamHandler(new RadiusParam());
        apiImpl.registerQueryParamHandler(new ChunkRadiusParam());
        apiImpl.registerQueryParamHandler(new TimeParam());
        apiImpl.registerQueryParamHandler(new BeforeParam());
        apiImpl.registerQueryParamHandler(new BlockParam());
        apiImpl.registerQueryParamHandler(new EntityParam());
        apiImpl.registerQueryParamHandler(new WorldParam());
        apiImpl.registerQueryParamHandler(new ItemNameParam());
        apiImpl.registerQueryParamHandler(new ItemLoreParam());
        apiImpl.registerQueryParamHandler(new ItemTagParam());
        apiImpl.registerQueryParamHandler(new EnchantParam());
        apiImpl.registerQueryParamHandler(new MessageParam());
        apiImpl.registerQueryParamHandler(new ContentParam());
        apiImpl.registerQueryParamHandler(new CauseParam());
        apiImpl.registerQueryParamHandler(new ItemMaterialParam());
        apiImpl.registerQueryParamHandler(new CustomItemParam());
        apiImpl.registerQueryParamHandler(new TargetParam());
        // ip:<addr> resolves to the player UUIDs that joined from this IP,
        // so /sg search a:break ip:1.2.3.4 returns break events by those
        // players (not just join records). Capped at the search-result
        // limit to bound the synchronous lookup.
        RecordStore ipResolverStore = recordStore;
        int ipResolverLimit = config.limits().searchResult();
        IpParam ipParam = new IpParam(ip -> {
            net.medievalrp.spyglass.api.query.QueryRequest joinReq =
                    new net.medievalrp.spyglass.api.query.QueryRequest(
                            java.util.List.of(
                                    new net.medievalrp.spyglass.api.query.QueryPredicate.Eq("event", "join"),
                                    new net.medievalrp.spyglass.api.query.QueryPredicate.Eq("address", ip)),
                            net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST,
                            ipResolverLimit,
                            java.util.EnumSet.noneOf(net.medievalrp.spyglass.api.query.Flag.class),
                            false);
            return ipResolverStore.querySummary(joinReq).records().stream()
                    .map(r -> r.source() == null ? null : r.source().playerId())
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        });
        apiImpl.registerQueryParamHandler(ipParam);
        apiImpl.registerQueryParamHandler(new RecipientParam());
        apiImpl.registerQueryParamHandler(new ServerParam(config.server().name()));

        Bukkit.getServicesManager().register(SpyglassApi.class, apiImpl, this, ServicePriority.Normal);

        // Synthesized rolled audit (#22): the engine never emits
        // per-block receipts. The operation record that searches
        // synthesize the rolled-* entries from is emitted by
        // RollbackService at completion.
        RollbackEngine engine = new RollbackEngine();
        engine.setCustomEffectLookup(apiImpl::rollbackEffectHandler);
        RollbackPhysicsBlocker physicsBlocker = new RollbackPhysicsBlocker();
        getServer().getPluginManager().registerEvents(physicsBlocker, this);
        engine.setPhysicsBlocker(physicsBlocker);
        engine.setTickBudgetMs(config.limits().rollbackTickBudgetMs());
        // Pool for the off-main-thread world-write phase. The apply
        // path's bulk LevelChunkSection.setBlockState loop runs here so
        // the main thread only handles the small post-processing (chunk
        // packet, tile entities, inverse build) per chunk. Server TPS
        // stays at ~20 even on multi-million-block rollbacks because
        // we're no longer monopolizing the server thread for the whole
        // rollback duration.
        //
        // Sized to the host: distinct chunks write to independent
        // LevelChunkSection palettes, and the 4-arg setBlockState takes
        // the useLocks=true (thread-safe) path, so the engine fans the
        // per-chunk palette writes across these threads — the dominant
        // phase of a large rollback. getChunk and all tile-entity /
        // finish work stay on the main thread (see RollbackEngine), so
        // the only NMS touched off-main is the locked section write.
        int worldWriteThreads = Math.max(2,
                Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
        final java.util.concurrent.atomic.AtomicInteger writeThreadSeq =
                new java.util.concurrent.atomic.AtomicInteger();
        this.worldWriteExecutor = java.util.concurrent.Executors.newFixedThreadPool(
                worldWriteThreads, r -> {
                    Thread t = new Thread(r, "Spyglass-WorldWriter-" + writeThreadSeq.incrementAndGet());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY);
                    return t;
                });
        engine.setWorldWriteExecutor(this.worldWriteExecutor);
        engine.setWorldWriteParallelism(worldWriteThreads);
        getLogger().info("Spyglass rollback world-write pool: " + worldWriteThreads + " threads");
        // Plugin reference so the engine can hold a chunk ticket per
        // chunk during the async write phase — chunks pinned loaded
        // until each chunk's main-thread post-processing completes.
        engine.setChunkTicketHolder(this);
        // Container salvage (#76): capture inventories a rollback would destroy
        // so they can be recovered via /sg inventory. Wired only when the
        // backend provides a salvage store (Mongo for now); the persist runs on
        // the virtual-thread query executor, never the main thread.
        if (salvageStore != null) {
            engine.setSalvageHook(new SalvageCapturer(salvageStore, queryExecutor, getLogger()));
            getLogger().info("Spyglass: container salvage capture enabled.");
        } else {
            getLogger().info("Spyglass: container salvage capture disabled for this backend.");
        }
        ServiceSupport serviceSupport = ServiceSupport.bukkit(this);

        QueryStringParser parser = new QueryStringParser(apiImpl, config);
        net.medievalrp.spyglass.plugin.command.service.IpQueryResolver ipQueryResolver =
                new net.medievalrp.spyglass.plugin.command.service.IpQueryResolver(
                        parser, ipParam, serviceSupport, getLogger());
        ResultRenderer renderer = new ResultRenderer(apiImpl, config);
        PageCache pageCache = new PageCache();
        getServer().getPluginManager().registerEvents(pageCache, this);

        HelpService helpService = new HelpService();
        SearchService searchService = new SearchService(apiImpl, parser, renderer, pageCache, serviceSupport, ipQueryResolver, getLogger());
        net.medievalrp.spyglass.plugin.command.service.RollbackJobQueue rollbackQueue =
                new net.medievalrp.spyglass.plugin.command.service.RollbackJobQueue();
        net.medievalrp.spyglass.plugin.command.service.RollbackResumeStore resumeStore =
                new net.medievalrp.spyglass.plugin.command.service.RollbackResumeStore(
                        getDataFolder().toPath(), getLogger());
        RollbackService rollbackService = new RollbackService(apiImpl, parser, config, engine, undoStack, serviceSupport, recorder, recordStore, getLogger(), rollbackQueue, resumeStore, ipQueryResolver);
        rollbackService.wireQueue();
        // On startup, surface any rollback that was in flight when
        // the JVM previously died. Each leftover marker becomes a
        // line in the server log; operators can /sg rbqueue resume
        // <id> to re-run, or /sg rbqueue cancel <id> to discard.
        var pendingResume = resumeStore.listPending();
        if (!pendingResume.isEmpty()) {
            getLogger().warning("Spyglass: " + pendingResume.size()
                    + " rollback(s) were interrupted by the previous shutdown. "
                    + "Run /sg rbqueue to view; /sg rbqueue resume <id> to re-run, "
                    + "/sg rbqueue cancel <id> to discard.");
            for (var s : pendingResume) {
                getLogger().warning("  • " + s.shortId() + " (" + s.mode() + ") by "
                        + s.operatorName() + " — query: " + s.query()
                        + " — started " + s.startedAt());
            }
        }
        UndoService undoService = new UndoService(engine, undoStack, serviceSupport, config,
                rollbackService, salvageStore);
        net.medievalrp.spyglass.plugin.command.service.RbqueueService rbqueueService =
                new net.medievalrp.spyglass.plugin.command.service.RbqueueService(
                        rollbackQueue, resumeStore, rollbackService, serviceSupport);
        ToolService toolService = new ToolService(
                toolStateStore, config.tool().material(), serviceSupport, getLogger());
        getServer().getPluginManager().registerEvents(
                new WandInteractListener(toolService, searchService, config), this);
        TeleportService teleportService = new TeleportService();

        // CoreProtect import (Task 9): a separate credentials file
        // (import.conf, data-folder root) + import history cache + async
        // service. .db files to import live in import/. Constructed here,
        // after recordStore/config/serviceSupport exist, so the command
        // layer below can wire /spyglass import.
        java.nio.file.Path importDir = getDataFolder().toPath().resolve("import");
        try {
            java.nio.file.Files.createDirectories(importDir);
        } catch (java.io.IOException ex) {
            getLogger().log(Level.WARNING, "Spyglass: could not create the import directory "
                    + importDir + "; /spyglass import file listing may be unavailable.", ex);
        }
        java.nio.file.Path importConfPath = getDataFolder().toPath().resolve("import.conf");
        if (java.nio.file.Files.notExists(importConfPath)) {
            saveResource("import.conf", false);
        }
        net.medievalrp.spyglass.plugin.imports.ImportConfig importConfig;
        try {
            importConfig = net.medievalrp.spyglass.plugin.imports.ImportConfig.loadFrom(importConfPath);
        } catch (java.io.IOException ex) {
            getLogger().log(Level.WARNING, "Spyglass: failed to load import.conf; MySQL import "
                    + "sources will be unavailable until this is fixed.", ex);
            importConfig = new net.medievalrp.spyglass.plugin.imports.ImportConfig(Map.of());
        }
        net.medievalrp.spyglass.plugin.imports.ImportHistoryStore importHistory =
                new net.medievalrp.spyglass.plugin.imports.ImportHistoryStore(getDataFolder().toPath());
        net.medievalrp.spyglass.plugin.imports.ImportService importService =
                new net.medievalrp.spyglass.plugin.imports.ImportService(
                        recordStore, config.database().backend(), serviceSupport,
                        Bukkit.getWorldContainer().toPath(), config.server().name(),
                        java.time.Duration.ofSeconds(config.storage().retention().seconds()),
                        config.limits().rollbackBatchSize(), importHistory, getLogger());

        // /spyglass migrate <backend>: copy the active backend's records into
        // another configured backend. The factory opens the TARGET store from
        // the same config blocks the boot switch reads; MigrateService owns
        // its lifecycle (opened per run, closed after).
        net.medievalrp.spyglass.plugin.migrate.MigrateService migrateService =
                new net.medievalrp.spyglass.plugin.migrate.MigrateService(
                        recordStore, config.database().backend(), config.database(),
                        serviceSupport,
                        target -> switch (target) {
                            case MONGO -> new MongoRecordStore(
                                    config.database().mongo().uri(), config.database().mongo().database(),
                                    config.database().mongo().collection(), new IndexManager(),
                                    retentionPolicy);
                            case CLICKHOUSE -> {
                                SpyglassConfig.ClickHouse ch = config.database().clickhouse();
                                yield new ClickHouseRecordStore(
                                        ch.host(), ch.port(), ch.database(), ch.table(),
                                        ch.user(), ch.password(), ch.ssl(), retentionPolicy);
                            }
                            case SQLITE -> {
                                java.nio.file.Path configured =
                                        java.nio.file.Path.of(config.database().sqlite().path());
                                yield new SqliteRecordStore(
                                        configured.isAbsolute()
                                                ? configured
                                                : getDataFolder().toPath().resolve(configured),
                                        false, retentionPolicy);
                            }
                            case MARIADB -> {
                                SpyglassConfig.MariaDb maria = config.database().mariadb();
                                yield new MariaDbRecordStore(
                                        maria.host(), maria.port(), maria.database(),
                                        maria.user(), maria.password(), maria.ssl(),
                                        retentionPolicy);
                            }
                        },
                        importService::isRunning,
                        config.limits().rollbackBatchSize(), getLogger());

        SpyglassSuggestions suggestions = new SpyglassSuggestions(apiImpl, importConfig, importDir);

        // Container salvage GUI + command (#76). The withdraw logger records a
        // salvage-withdraw event (reusing ContainerWithdrawRecord) so every
        // recovery is auditable; the GUI persists off-main on the query pool.
        SalvageWithdrawLogger salvageWithdrawLogger = (player, snap, taken, amount) -> {
            net.medievalrp.spyglass.api.util.BlockLocation salvageLoc =
                    new net.medievalrp.spyglass.api.util.BlockLocation(
                            snap.worldId(), snap.worldName(), snap.x(), snap.y(), snap.z());
            net.medievalrp.spyglass.api.event.RecordContext salvageCtx =
                    support.playerContext(player, salvageLoc);
            net.medievalrp.spyglass.api.event.StoredItem salvageStored =
                    net.medievalrp.spyglass.plugin.util.ItemSerialization.storedItem(0, taken);
            recorder.record(net.medievalrp.spyglass.api.event.ContainerWithdrawRecord.of(
                    salvageCtx, "salvage-withdraw", taken.getType().name(),
                    snap.containerType(), 0, amount, salvageStored, null));
        };
        // One shared, dupe-guarded extract engine behind both the GUI and the
        // command. On versions InvUI 1.49 supports (1.x) we build the InvUI GUI;
        // on 26.x SalvageViews returns null and salvage is command-only (no
        // unverified inventory-click surface). The InvUI view manages its own
        // click listeners, so no registerEvents here.
        SalvageWithdrawals salvageWithdrawals = salvageStore == null ? null
                : new SalvageWithdrawals(salvageStore, queryExecutor, salvageWithdrawLogger, getLogger());
        SalvageView salvageView = salvageStore == null ? null
                : SalvageViews.guiOrNull(this, getServer().getBukkitVersion(), salvageStore,
                        queryExecutor, serviceSupport::onMainThread, salvageWithdrawals,
                        config.limits().searchResult(), getLogger());
        SalvageService salvageService = new SalvageService(
                salvageStore, salvageView, salvageWithdrawals, config.limits().searchResult(), serviceSupport);
        // #168: /spyglass stats. Null ingestStats (analytics off) => the command
        // explains how to enable it.
        StatsService statsService = new StatsService(ingestStats, recorder::spillSnapshot);

        SpyglassCommands commands = new SpyglassCommands(
                this,
                apiImpl,
                helpService,
                searchService,
                rollbackService,
                undoService,
                rbqueueService,
                pageCache,
                toolService,
                teleportService,
                salvageService,
                statsService,
                suggestions,
                importService,
                importConfig,
                importDir,
                migrateService,
                config.commands().sAlias());
        commands.register();

        if (config.worldedit().enabled() && isWorldEditInstalled()) {
            try {
                worldEditSubscriber = new WorldEditSubscriber(recorder, support, queryExecutor, this, getLogger());
                worldEditSubscriber.register();
            } catch (Throwable thrown) {
                getLogger().warning("Spyglass: WorldEdit integration failed to initialize: " + thrown);
                worldEditSubscriber = null;
            }
        } else if (!config.worldedit().enabled() && isWorldEditInstalled()) {
            getLogger().info("Spyglass: WorldEdit logging disabled (worldedit.enabled = false);"
                    + " WorldEdit and FAWE edits are not recorded.");
        }
        // Always register the lifecycle listener so WE hot-load (e.g.
        // /plugman load) can wire up recording mid-session without a
        // server restart. If WE is already running, the existing
        // subscriber is handed in and the listener only acts on a
        // future disable. The gate is read live so the #332 toggle (and a
        // later /sg reload of it) governs hot-load registration too.
        worldEditLifecycle = new WorldEditLifecycleListener(
                recorder, support, queryExecutor, this, getLogger(), worldEditSubscriber,
                () -> config.worldedit().enabled());
        getServer().getPluginManager().registerEvents(worldEditLifecycle, this);

        // bStats anonymous usage metrics. The org.bstats package is relocated
        // into net.medievalrp.spyglass.libs.bstats at shade time, so this can't
        // clash with another plugin's bundled bStats. Opt-out is either
        // metrics.enabled = false here or the server-wide bStats config the
        // library writes. One custom chart reports the storage backend in use
        // (sqlite / mongo / clickhouse / mariadb); the rest is bStats' default,
        // non-identifying platform data (server software, player count, Java).
        if (config.metrics().enabled()) {
            this.metrics = new Metrics(this, BSTATS_PLUGIN_ID);
            this.metrics.addCustomChart(new SimplePie("storage_backend",
                    () -> config.database().backend().name().toLowerCase(java.util.Locale.ROOT)));
            getLogger().info("Spyglass: bStats metrics enabled (opt out with metrics.enabled=false).");
        }

        // #128: sweep expired FallingBlockTracker cells on a repeating async task.
        // purgeExpired() only touches a ConcurrentHashMap -- no Bukkit world access --
        // so running it off the main thread is safe. The period (1200 ticks = 60 s)
        // is 2x the TTL (30 s), meaning every cohort of cascade cells is guaranteed
        // at least one full sweep opportunity before a second TTL window elapses.
        // Delay matches the period so the first pass is not immediately at boot.
        // #226: the transfer dedup is a ConcurrentHashMap on the same 30 s
        // window, so it piggybacks on this sweep - same off-main, no
        // Bukkit-access safety as the falling-block purge.
        final long PURGE_PERIOD_TICKS = 1200L; // 60 s at 20 TPS
        this.fallingBlockPurgeTask = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, () -> {
                    FallingBlockTracker.purgeExpired();
                    transferDedup.purgeExpired();
                }, PURGE_PERIOD_TICKS, PURGE_PERIOD_TICKS);

        // #168: periodic ingest analytics report (off the main thread; only
        // reads concurrent counters + cheap gauges). Off unless analytics.enabled.
        if (ingestStats != null) {
            long intervalTicks = Math.max(20L, config.analytics().interval().seconds() * 20L);
            this.analyticsTask = getServer().getScheduler().runTaskTimerAsynchronously(
                    this, new IngestStatsReporter(ingestStats, getLogger()),
                    intervalTicks, intervalTicks);
            getLogger().info("Spyglass analytics enabled: ingest report every "
                    + config.analytics().interval().seconds() + "s and via /spyglass stats.");
        }

        getLogger().info("Spyglass enabled; events=" + enabledEvents);
    }

    private boolean isWorldEditInstalled() {
        return isLive("WorldEdit") || isLive("FastAsyncWorldEdit");
    }

    /**
     * Plugin is present AND currently enabled. A softdepend that's
     * been disabled by another plugin during startup (or disabled via
     * plugman before we hit onEnable) shouldn't trigger wire-up — the
     * lifecycle listener will pick it up later if it enables again.
     */
    private boolean isLive(String pluginName) {
        var plugin = getServer().getPluginManager().getPlugin(pluginName);
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public void onDisable() {
        // Cancel the FallingBlockTracker purge task before touching any other
        // state so it cannot fire concurrently with teardown.
        if (fallingBlockPurgeTask != null) {
            fallingBlockPurgeTask.cancel();
            fallingBlockPurgeTask = null;
        }
        // #168: stop the analytics report task before teardown.
        if (analyticsTask != null) {
            analyticsTask.cancel();
            analyticsTask = null;
        }
        // Stop the bStats submit scheduler first so it can't fire mid-teardown.
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
        // Prefer the lifecycle-tracked subscriber - if WE was hot-
        // loaded/unloaded during the session, the initial field may be
        // stale.
        WorldEditSubscriber active = worldEditLifecycle != null
                ? worldEditLifecycle.currentSubscriber()
                : worldEditSubscriber;
        if (active != null) {
            try {
                active.unregister();
            } catch (Throwable ignored) {
            }
        }
        // Drain the off-thread serialization stage BEFORE the recorder,
        // so any in-flight snapshots (deferred item pickups) finish
        // serializing and reach the recorder's queue before it drains.
        // Ordering matters: reversed, the recorder would close while
        // pickups were still mid-serialization and lose them.
        if (deferredSerializer != null) {
            deferredSerializer.shutdown(Duration.parse("2s"));
            deferredSerializer = null;
        }
        if (recorder != null) {
            try {
                AsyncRecorder.ShutdownReport report = recorder.shutdown(Duration.parse("5s"));
                getLogger().info("Recorder drained=" + report.drained()
                        + " dropped=" + report.dropped()
                        + " remaining=" + report.remaining());
            } catch (Exception ex) {
                getLogger().warning("Recorder shutdown failed: " + ex.getMessage());
            }
        }
        if (recordStore != null) {
            try {
                recordStore.close();
            } catch (Exception ignored) {
            }
        }
        // Shut down the off-thread palette-write worker. Without this
        // the worker thread survives across /reload and accumulates one
        // leak per reload cycle until JVM exit. Wait briefly for any
        // in-flight rollback to finish; if it doesn't, hard-kill the
        // worker — the rollback completion path on the main thread
        // would no longer have a Bukkit scheduler to run on anyway.
        if (worldWriteExecutor != null) {
            worldWriteExecutor.shutdown();
            try {
                if (!worldWriteExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    worldWriteExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                worldWriteExecutor.shutdownNow();
            }
            worldWriteExecutor = null;
        }
        Bukkit.getServicesManager().unregisterAll(this);
    }
}
