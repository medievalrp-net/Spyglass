package net.medievalrp.spyglass.plugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.api.SpyglassApiImpl;
import net.medievalrp.spyglass.plugin.command.SpyglassCommands;
import net.medievalrp.spyglass.plugin.command.SpyglassSuggestions;
import net.medievalrp.spyglass.plugin.command.PageCache;
import net.medievalrp.spyglass.plugin.command.param.BlockParam;
import net.medievalrp.spyglass.plugin.command.param.CauseParam;
import net.medievalrp.spyglass.plugin.command.param.EnchantParam;
import net.medievalrp.spyglass.plugin.command.param.EntityParam;
import net.medievalrp.spyglass.plugin.command.param.EventParam;
import net.medievalrp.spyglass.plugin.command.param.IpParam;
import net.medievalrp.spyglass.plugin.command.param.ItemLoreParam;
import net.medievalrp.spyglass.plugin.command.param.ItemMaterialParam;
import net.medievalrp.spyglass.plugin.command.param.ItemNameParam;
import net.medievalrp.spyglass.plugin.command.param.MessageParam;
import net.medievalrp.spyglass.plugin.command.param.PlayerParam;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import net.medievalrp.spyglass.plugin.command.param.RadiusParam;
import net.medievalrp.spyglass.plugin.command.param.RecipientParam;
import net.medievalrp.spyglass.plugin.command.param.TargetParam;
import net.medievalrp.spyglass.plugin.command.param.TimeParam;
import net.medievalrp.spyglass.plugin.command.param.WorldParam;
import net.medievalrp.spyglass.plugin.command.render.ResultRenderer;
import net.medievalrp.spyglass.plugin.command.service.EventsService;
import net.medievalrp.spyglass.plugin.command.service.HelpService;
import net.medievalrp.spyglass.plugin.command.service.PageService;
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
import net.medievalrp.spyglass.plugin.listener.block.ContainerDropListener;
import net.medievalrp.spyglass.plugin.listener.block.DependantBreakListener;
import net.medievalrp.spyglass.plugin.listener.block.MultiBlockBreakListener;
import net.medievalrp.spyglass.plugin.listener.chat.ChatListener;
import net.medievalrp.spyglass.plugin.listener.chat.CommandListener;
import net.medievalrp.spyglass.plugin.listener.container.ContainerDragListener;
import net.medievalrp.spyglass.plugin.listener.container.ContainerInteractListener;
import net.medievalrp.spyglass.plugin.listener.container.ContainerTransactionListener;
import net.medievalrp.spyglass.plugin.listener.entity.ArmorStandManipulateListener;
import net.medievalrp.spyglass.plugin.listener.entity.EntityDamageListener;
import net.medievalrp.spyglass.plugin.listener.entity.EntityDeathListener;
import net.medievalrp.spyglass.plugin.listener.entity.EntityDismountListener;
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
import net.medievalrp.spyglass.plugin.listener.item.ItemDropListener;
import net.medievalrp.spyglass.plugin.listener.item.ItemPickupListener;
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
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import net.medievalrp.spyglass.plugin.storage.IndexManager;
import net.medievalrp.spyglass.plugin.storage.MongoRecordStore;
import net.medievalrp.spyglass.plugin.command.service.tool.ToolStateStore;
import net.medievalrp.spyglass.plugin.command.service.tool.WandInteractListener;
import net.medievalrp.spyglass.plugin.worldedit.WorldEditLifecycleListener;
import net.medievalrp.spyglass.plugin.worldedit.WorldEditSubscriber;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class SpyglassPlugin extends JavaPlugin {

    private AsyncRecorder recorder;
    private MongoRecordStore recordStore;
    private Executor queryExecutor;
    private SpyglassConfig config;
    private WorldEditSubscriber worldEditSubscriber;
    private WorldEditLifecycleListener worldEditLifecycle;

    @Override
    public void onEnable() {
        try {
            config = SpyglassConfig.load(this);
        } catch (Exception ex) {
            getLogger().severe("Failed to load config: " + ex.getMessage());
            setEnabled(false);
            return;
        }

        try {
            IndexManager indexManager = new IndexManager();
            recordStore = new MongoRecordStore(config.database(), indexManager);
        } catch (Exception ex) {
            getLogger().severe("Failed to connect to MongoDB: " + ex.getMessage());
            setEnabled(false);
            return;
        }

        queryExecutor = Executors.newVirtualThreadPerTaskExecutor();
        recorder = new AsyncRecorder(config.storage().queueCapacity(), recordStore, getLogger());

        Set<String> enabledEvents = config.events().entrySet().stream()
                .filter(entry -> entry.getValue().enabled())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());

        RecordingSupport support = new RecordingSupport(config.storage().retention());
        DelayedInteractionTracker delayedTracker = new DelayedInteractionTracker(this);

        // Every recording listener in one list. `events()` declares the event
        // names each emits; we register with Bukkit only when at least one is
        // enabled in config.
        List<RecordingListener> listeners = List.of(
                new BlockBreakListener(recorder, support),
                new MultiBlockBreakListener(recorder, support),
                new DependantBreakListener(recorder, support),
                new BlockExplodeListener(recorder, support, this),
                new EntityExplodeListener(recorder, support),
                new BlockBurnListener(recorder, support, this),
                new BlockPlaceListener(recorder, support),
                new BlockMultiPlaceListener(recorder, support),
                new ContainerTransactionListener(recorder, support),
                new ContainerDragListener(recorder, support),
                new ContainerInteractListener(recorder, support),
                new BlockUseListener(recorder, support),
                new ContainerDropListener(recorder, support),
                new ChatListener(recorder, support),
                new CommandListener(recorder, support),
                new JoinListener(recorder, support),
                new QuitListener(recorder, support),
                new LeavesDecayListener(recorder, support),
                new BlockFadeListener(recorder, support),
                new BlockFormListener(recorder, support),
                new BlockGrowListener(recorder, support),
                new StructureGrowListener(recorder, support),
                new BlockIgniteListener(recorder, support, this),
                new ItemDropListener(recorder, support),
                new ItemPickupListener(recorder, support),
                new CreativeCloneListener(recorder, support),
                new TeleportListener(recorder, support),
                new EntityDeathListener(recorder, support),
                new EntityDamageListener(recorder, support),
                new EntityMountListener(recorder, support),
                new EntityDismountListener(recorder, support),
                new ArmorStandManipulateListener(recorder, support),
                new ItemFrameInteractListener(recorder, support),
                new EntityNamingListener(recorder, support),
                new BookshelfListener(recorder, support),
                new DecoratedPotListener(recorder, support),
                new ShulkerTransactionListener(recorder, support),
                new BundleTransactionListener(recorder, support, this),
                new CrafterListener(recorder, support),
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

        SpyglassApiImpl apiImpl = new SpyglassApiImpl(recorder, recordStore, queryExecutor, enabledEvents);
        apiImpl.registerQueryParamHandler(new PlayerParam());
        apiImpl.registerQueryParamHandler(new EventParam(enabledEvents));
        apiImpl.registerQueryParamHandler(new RadiusParam());
        apiImpl.registerQueryParamHandler(new TimeParam());
        apiImpl.registerQueryParamHandler(new BlockParam());
        apiImpl.registerQueryParamHandler(new EntityParam());
        apiImpl.registerQueryParamHandler(new WorldParam());
        apiImpl.registerQueryParamHandler(new ItemNameParam());
        apiImpl.registerQueryParamHandler(new ItemLoreParam());
        apiImpl.registerQueryParamHandler(new EnchantParam());
        apiImpl.registerQueryParamHandler(new MessageParam());
        apiImpl.registerQueryParamHandler(new CauseParam());
        apiImpl.registerQueryParamHandler(new ItemMaterialParam());
        apiImpl.registerQueryParamHandler(new TargetParam());
        apiImpl.registerQueryParamHandler(new IpParam());
        apiImpl.registerQueryParamHandler(new RecipientParam());

        Bukkit.getServicesManager().register(SpyglassApi.class, apiImpl, this, ServicePriority.Normal);

        RollbackEngine engine = new RollbackEngine();
        UndoStack undoStack = new UndoStack(recordStore.database(), recordStore.codecRegistry());
        ServiceSupport serviceSupport = ServiceSupport.bukkit(this);

        QueryStringParser parser = new QueryStringParser(apiImpl, config);
        ResultRenderer renderer = new ResultRenderer(apiImpl, config);
        PageCache pageCache = new PageCache();
        getServer().getPluginManager().registerEvents(pageCache, this);

        HelpService helpService = new HelpService();
        EventsService eventsService = new EventsService(apiImpl);
        SearchService searchService = new SearchService(apiImpl, parser, renderer, pageCache, serviceSupport, getLogger());
        RollbackService rollbackService = new RollbackService(apiImpl, parser, config, engine, undoStack, serviceSupport, getLogger());
        UndoService undoService = new UndoService(engine, undoStack, serviceSupport);
        PageService pageService = new PageService(pageCache);
        ToolStateStore toolStateStore = new ToolStateStore(recordStore.database(), getLogger());
        ToolService toolService = new ToolService(toolStateStore, config.tool().material());
        getServer().getPluginManager().registerEvents(
                new WandInteractListener(toolService, searchService, config), this);
        TeleportService teleportService = new TeleportService();
        SpyglassSuggestions suggestions = new SpyglassSuggestions(apiImpl);

        SpyglassCommands commands = new SpyglassCommands(
                this,
                helpService,
                eventsService,
                searchService,
                rollbackService,
                undoService,
                pageService,
                toolService,
                teleportService,
                suggestions);
        commands.register();

        if (isWorldEditInstalled()) {
            try {
                worldEditSubscriber = new WorldEditSubscriber(recorder, support, getLogger());
                worldEditSubscriber.register();
            } catch (Throwable thrown) {
                getLogger().warning("Spyglass: WorldEdit integration failed to initialize: " + thrown);
                worldEditSubscriber = null;
            }
        }
        // Always register the lifecycle listener so WE hot-load (e.g.
        // /plugman load) can wire up recording mid-session without a
        // server restart. If WE is already running, the existing
        // subscriber is handed in and the listener only acts on a
        // future disable.
        worldEditLifecycle = new WorldEditLifecycleListener(
                recorder, support, getLogger(), worldEditSubscriber);
        getServer().getPluginManager().registerEvents(worldEditLifecycle, this);

        getLogger().info("Spyglass enabled; events=" + enabledEvents);
    }

    private boolean isWorldEditInstalled() {
        return getServer().getPluginManager().getPlugin("WorldEdit") != null
                || getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
    }

    @Override
    public void onDisable() {
        // Prefer the lifecycle-tracked subscriber — if WE was hot-
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
        Bukkit.getServicesManager().unregisterAll(this);
    }
}
