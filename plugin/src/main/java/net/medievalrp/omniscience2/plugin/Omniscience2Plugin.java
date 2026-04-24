package net.medievalrp.omniscience2.plugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import net.medievalrp.omniscience2.api.Omniscience2Api;
import net.medievalrp.omniscience2.api.util.Duration;
import net.medievalrp.omniscience2.plugin.api.Omniscience2ApiImpl;
import net.medievalrp.omniscience2.plugin.command.OmniCommands;
import net.medievalrp.omniscience2.plugin.command.OmniSuggestions;
import net.medievalrp.omniscience2.plugin.command.PageCache;
import net.medievalrp.omniscience2.plugin.command.param.BlockParam;
import net.medievalrp.omniscience2.plugin.command.param.CauseParam;
import net.medievalrp.omniscience2.plugin.command.param.EnchantParam;
import net.medievalrp.omniscience2.plugin.command.param.EntityParam;
import net.medievalrp.omniscience2.plugin.command.param.EventParam;
import net.medievalrp.omniscience2.plugin.command.param.ItemLoreParam;
import net.medievalrp.omniscience2.plugin.command.param.ItemMaterialParam;
import net.medievalrp.omniscience2.plugin.command.param.ItemNameParam;
import net.medievalrp.omniscience2.plugin.command.param.MessageParam;
import net.medievalrp.omniscience2.plugin.command.param.PlayerParam;
import net.medievalrp.omniscience2.plugin.command.param.QueryStringParser;
import net.medievalrp.omniscience2.plugin.command.param.RadiusParam;
import net.medievalrp.omniscience2.plugin.command.param.TargetParam;
import net.medievalrp.omniscience2.plugin.command.param.TimeParam;
import net.medievalrp.omniscience2.plugin.command.param.WorldParam;
import net.medievalrp.omniscience2.plugin.command.render.ResultRenderer;
import net.medievalrp.omniscience2.plugin.command.service.EventsService;
import net.medievalrp.omniscience2.plugin.command.service.HelpService;
import net.medievalrp.omniscience2.plugin.command.service.PageService;
import net.medievalrp.omniscience2.plugin.command.service.RollbackService;
import net.medievalrp.omniscience2.plugin.command.service.SearchService;
import net.medievalrp.omniscience2.plugin.command.service.ServiceSupport;
import net.medievalrp.omniscience2.plugin.command.service.TeleportService;
import net.medievalrp.omniscience2.plugin.command.service.ToolService;
import net.medievalrp.omniscience2.plugin.command.service.UndoService;
import net.medievalrp.omniscience2.plugin.config.Omniscience2Config;
import net.medievalrp.omniscience2.plugin.listener.RecordingSupport;
import net.medievalrp.omniscience2.plugin.listener.RecordingListener;
import net.medievalrp.omniscience2.plugin.listener.block.BlockBreakListener;
import net.medievalrp.omniscience2.plugin.listener.block.BlockMultiPlaceListener;
import net.medievalrp.omniscience2.plugin.listener.block.BlockPlaceListener;
import net.medievalrp.omniscience2.plugin.listener.block.ContainerDropListener;
import net.medievalrp.omniscience2.plugin.listener.block.DependantBreakListener;
import net.medievalrp.omniscience2.plugin.listener.block.MultiBlockBreakListener;
import net.medievalrp.omniscience2.plugin.listener.chat.ChatListener;
import net.medievalrp.omniscience2.plugin.listener.chat.CommandListener;
import net.medievalrp.omniscience2.plugin.listener.container.ContainerDragListener;
import net.medievalrp.omniscience2.plugin.listener.container.ContainerInteractListener;
import net.medievalrp.omniscience2.plugin.listener.container.ContainerTransactionListener;
import net.medievalrp.omniscience2.plugin.listener.entity.ArmorStandManipulateListener;
import net.medievalrp.omniscience2.plugin.listener.entity.EntityDamageListener;
import net.medievalrp.omniscience2.plugin.listener.entity.EntityDeathListener;
import net.medievalrp.omniscience2.plugin.listener.entity.EntityDismountListener;
import net.medievalrp.omniscience2.plugin.listener.entity.EntityMountListener;
import net.medievalrp.omniscience2.plugin.listener.environment.BlockBurnListener;
import net.medievalrp.omniscience2.plugin.listener.environment.BlockExplodeListener;
import net.medievalrp.omniscience2.plugin.listener.environment.BlockFadeListener;
import net.medievalrp.omniscience2.plugin.listener.environment.BlockFormListener;
import net.medievalrp.omniscience2.plugin.listener.environment.BlockGrowListener;
import net.medievalrp.omniscience2.plugin.listener.environment.BlockIgniteListener;
import net.medievalrp.omniscience2.plugin.listener.environment.EntityExplodeListener;
import net.medievalrp.omniscience2.plugin.listener.environment.LeavesDecayListener;
import net.medievalrp.omniscience2.plugin.listener.environment.StructureGrowListener;
import net.medievalrp.omniscience2.plugin.listener.item.ItemDropListener;
import net.medievalrp.omniscience2.plugin.listener.item.ItemPickupListener;
import net.medievalrp.omniscience2.plugin.listener.modern.BookshelfListener;
import net.medievalrp.omniscience2.plugin.listener.modern.BrushListener;
import net.medievalrp.omniscience2.plugin.listener.modern.BundleTransactionListener;
import net.medievalrp.omniscience2.plugin.listener.modern.CrafterListener;
import net.medievalrp.omniscience2.plugin.listener.modern.DecoratedPotListener;
import net.medievalrp.omniscience2.plugin.listener.modern.DelayedInteractionTracker;
import net.medievalrp.omniscience2.plugin.listener.modern.SculkListener;
import net.medievalrp.omniscience2.plugin.listener.modern.ShulkerTransactionListener;
import net.medievalrp.omniscience2.plugin.listener.modern.VaultListener;
import net.medievalrp.omniscience2.plugin.listener.player.BlockUseListener;
import net.medievalrp.omniscience2.plugin.listener.player.JoinListener;
import net.medievalrp.omniscience2.plugin.listener.player.QuitListener;
import net.medievalrp.omniscience2.plugin.listener.player.TeleportListener;
import net.medievalrp.omniscience2.plugin.pipeline.AsyncRecorder;
import net.medievalrp.omniscience2.plugin.rollback.RollbackEngine;
import net.medievalrp.omniscience2.plugin.rollback.UndoStack;
import net.medievalrp.omniscience2.plugin.storage.IndexManager;
import net.medievalrp.omniscience2.plugin.storage.MongoRecordStore;
import net.medievalrp.omniscience2.plugin.command.service.tool.ToolStateStore;
import net.medievalrp.omniscience2.plugin.command.service.tool.WandInteractListener;
import net.medievalrp.omniscience2.plugin.worldedit.WorldEditSubscriber;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class Omniscience2Plugin extends JavaPlugin {

    private AsyncRecorder recorder;
    private MongoRecordStore recordStore;
    private Executor queryExecutor;
    private Omniscience2Config config;
    private WorldEditSubscriber worldEditSubscriber;

    @Override
    public void onEnable() {
        try {
            config = Omniscience2Config.load(this);
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
                new TeleportListener(recorder, support),
                new EntityDeathListener(recorder, support),
                new EntityDamageListener(recorder, support),
                new EntityMountListener(recorder, support),
                new EntityDismountListener(recorder, support),
                new ArmorStandManipulateListener(recorder, support),
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

        Omniscience2ApiImpl apiImpl = new Omniscience2ApiImpl(recorder, recordStore, queryExecutor, enabledEvents);
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

        Bukkit.getServicesManager().register(Omniscience2Api.class, apiImpl, this, ServicePriority.Normal);

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
        OmniSuggestions suggestions = new OmniSuggestions(apiImpl);

        OmniCommands commands = new OmniCommands(
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
                getLogger().warning("Omniscience2: WorldEdit integration failed to initialize: " + thrown);
                worldEditSubscriber = null;
            }
        }

        getLogger().info("Omniscience2 enabled; events=" + enabledEvents);
    }

    private boolean isWorldEditInstalled() {
        return getServer().getPluginManager().getPlugin("WorldEdit") != null
                || getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
    }

    @Override
    public void onDisable() {
        if (worldEditSubscriber != null) {
            try {
                worldEditSubscriber.unregister();
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
