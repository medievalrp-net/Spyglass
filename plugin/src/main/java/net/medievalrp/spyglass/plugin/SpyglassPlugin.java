package net.medievalrp.spyglass.plugin;

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
import net.medievalrp.spyglass.plugin.command.param.EntityParam;
import net.medievalrp.spyglass.plugin.command.param.EventParam;
import net.medievalrp.spyglass.plugin.command.param.PlayerParam;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import net.medievalrp.spyglass.plugin.command.param.RadiusParam;
import net.medievalrp.spyglass.plugin.command.param.TimeParam;
import net.medievalrp.spyglass.plugin.command.param.WorldParam;
import net.medievalrp.spyglass.plugin.command.render.ResultRenderer;
import net.medievalrp.spyglass.plugin.command.service.EventsService;
import net.medievalrp.spyglass.plugin.command.service.HelpService;
import net.medievalrp.spyglass.plugin.command.service.PageService;
import net.medievalrp.spyglass.plugin.command.service.RollbackService;
import net.medievalrp.spyglass.plugin.command.service.SearchService;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import net.medievalrp.spyglass.plugin.command.service.ToolService;
import net.medievalrp.spyglass.plugin.command.service.UndoService;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.listener.block.BlockBreakExtractor;
import net.medievalrp.spyglass.plugin.listener.block.BlockMultiPlaceExtractor;
import net.medievalrp.spyglass.plugin.listener.block.BlockPlaceExtractor;
import net.medievalrp.spyglass.plugin.listener.chat.ChatExtractor;
import net.medievalrp.spyglass.plugin.listener.chat.CommandExtractor;
import net.medievalrp.spyglass.plugin.listener.container.ContainerDragExtractor;
import net.medievalrp.spyglass.plugin.listener.container.ContainerTransactionExtractor;
import net.medievalrp.spyglass.plugin.listener.entity.ArmorStandManipulateExtractor;
import net.medievalrp.spyglass.plugin.listener.entity.EntityDamageExtractor;
import net.medievalrp.spyglass.plugin.listener.entity.EntityDeathExtractor;
import net.medievalrp.spyglass.plugin.listener.entity.EntityDismountExtractor;
import net.medievalrp.spyglass.plugin.listener.entity.EntityMountExtractor;
import net.medievalrp.spyglass.plugin.listener.environment.BlockExplodeExtractor;
import net.medievalrp.spyglass.plugin.listener.environment.BlockFadeExtractor;
import net.medievalrp.spyglass.plugin.listener.environment.BlockFormExtractor;
import net.medievalrp.spyglass.plugin.listener.environment.BlockGrowExtractor;
import net.medievalrp.spyglass.plugin.listener.environment.BlockIgniteExtractor;
import net.medievalrp.spyglass.plugin.listener.environment.EntityExplodeExtractor;
import net.medievalrp.spyglass.plugin.listener.environment.LeavesDecayExtractor;
import net.medievalrp.spyglass.plugin.listener.environment.StructureGrowExtractor;
import net.medievalrp.spyglass.plugin.listener.item.ItemDropExtractor;
import net.medievalrp.spyglass.plugin.listener.item.ItemPickupExtractor;
import net.medievalrp.spyglass.plugin.listener.modern.BookshelfExtractor;
import net.medievalrp.spyglass.plugin.listener.modern.BrushExtractor;
import net.medievalrp.spyglass.plugin.listener.modern.CrafterExtractor;
import net.medievalrp.spyglass.plugin.listener.modern.DecoratedPotExtractor;
import net.medievalrp.spyglass.plugin.listener.modern.DelayedInteractionTracker;
import net.medievalrp.spyglass.plugin.listener.modern.SculkExtractor;
import net.medievalrp.spyglass.plugin.listener.modern.ShulkerTransactionExtractor;
import net.medievalrp.spyglass.plugin.listener.modern.VaultExtractor;
import net.medievalrp.spyglass.plugin.listener.player.JoinExtractor;
import net.medievalrp.spyglass.plugin.listener.player.QuitExtractor;
import net.medievalrp.spyglass.plugin.listener.player.TeleportExtractor;
import net.medievalrp.spyglass.plugin.migration.MigrationCommand;
import net.medievalrp.spyglass.plugin.migration.MigrationService;
import net.medievalrp.spyglass.plugin.migration.V1ItemDecoder;
import net.medievalrp.spyglass.plugin.migration.V1ToV2Translator;
import net.medievalrp.spyglass.plugin.migration.WorldNameLookup;
import net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder;
import net.medievalrp.spyglass.plugin.pipeline.ExtractorRegistry;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import net.medievalrp.spyglass.plugin.storage.IndexManager;
import net.medievalrp.spyglass.plugin.storage.MongoRecordStore;
import net.medievalrp.spyglass.plugin.tool.ToolStateStore;
import net.medievalrp.spyglass.plugin.tool.WandInteractListener;
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

        ExtractorSupport support = new ExtractorSupport(config.storage().retention());
        ExtractorRegistry registry = new ExtractorRegistry(recorder);
        if (enabledEvents.contains("break")) {
            registry.register(this, new BlockBreakExtractor(support));
            registry.register(this, new BlockExplodeExtractor(support));
            registry.register(this, new EntityExplodeExtractor(support));
        }
        if (enabledEvents.contains("place")) {
            registry.register(this, new BlockPlaceExtractor(support));
            registry.register(this, new BlockMultiPlaceExtractor(support));
        }
        if (enabledEvents.contains("deposit") || enabledEvents.contains("withdraw")) {
            registry.register(this, new ContainerTransactionExtractor(support));
            registry.register(this, new ContainerDragExtractor(support));
        }
        if (enabledEvents.contains("say")) registry.register(this, new ChatExtractor(support));
        if (enabledEvents.contains("command")) registry.register(this, new CommandExtractor(support));
        if (enabledEvents.contains("join")) registry.register(this, new JoinExtractor(support));
        if (enabledEvents.contains("quit")) registry.register(this, new QuitExtractor(support));
        if (enabledEvents.contains("decay")) {
            registry.register(this, new LeavesDecayExtractor(support));
            registry.register(this, new BlockFadeExtractor(support));
        }
        if (enabledEvents.contains("form")) registry.register(this, new BlockFormExtractor(support));
        if (enabledEvents.contains("grow")) {
            registry.register(this, new BlockGrowExtractor(support));
            registry.register(this, new StructureGrowExtractor(support));
        }
        if (enabledEvents.contains("ignite")) registry.register(this, new BlockIgniteExtractor(support));
        if (enabledEvents.contains("drop")) registry.register(this, new ItemDropExtractor(support));
        if (enabledEvents.contains("pickup")) registry.register(this, new ItemPickupExtractor(support));
        if (enabledEvents.contains("teleport")) registry.register(this, new TeleportExtractor(support));
        if (enabledEvents.contains("death")) registry.register(this, new EntityDeathExtractor(support));
        if (enabledEvents.contains("hit") || enabledEvents.contains("shot"))
            registry.register(this, new EntityDamageExtractor(support));
        if (enabledEvents.contains("mount")) registry.register(this, new EntityMountExtractor(support));
        if (enabledEvents.contains("dismount")) registry.register(this, new EntityDismountExtractor(support));
        if (enabledEvents.contains("entity-deposit") || enabledEvents.contains("entity-withdraw"))
            registry.register(this, new ArmorStandManipulateExtractor(support));
        if (enabledEvents.contains("bookshelf-insert") || enabledEvents.contains("bookshelf-remove"))
            registry.register(this, new BookshelfExtractor(support));
        if (enabledEvents.contains("pot-insert") || enabledEvents.contains("pot-remove"))
            registry.register(this, new DecoratedPotExtractor(support));
        if (enabledEvents.contains("shulker-deposit") || enabledEvents.contains("shulker-withdraw"))
            registry.register(this, new ShulkerTransactionExtractor(support));
        if (enabledEvents.contains("crafter")) registry.register(this, new CrafterExtractor(support));
        if (enabledEvents.contains("sculk")) registry.register(this, new SculkExtractor(support));
        DelayedInteractionTracker delayedTracker = new DelayedInteractionTracker(this);
        if (enabledEvents.contains("brush")) {
            getServer().getPluginManager().registerEvents(new BrushExtractor(recorder, support, delayedTracker), this);
        }
        if (enabledEvents.contains("vault")) {
            getServer().getPluginManager().registerEvents(new VaultExtractor(recorder, support, delayedTracker), this);
        }

        SpyglassApiImpl apiImpl = new SpyglassApiImpl(recorder, recordStore, queryExecutor, enabledEvents);
        apiImpl.registerQueryParamHandler(new PlayerParam());
        apiImpl.registerQueryParamHandler(new EventParam(enabledEvents));
        apiImpl.registerQueryParamHandler(new RadiusParam());
        apiImpl.registerQueryParamHandler(new TimeParam());
        apiImpl.registerQueryParamHandler(new BlockParam());
        apiImpl.registerQueryParamHandler(new EntityParam());
        apiImpl.registerQueryParamHandler(new WorldParam());

        Bukkit.getServicesManager().register(SpyglassApi.class, apiImpl, this, ServicePriority.Normal);

        RollbackEngine engine = new RollbackEngine();
        UndoStack undoStack = new UndoStack(recordStore.database(), recordStore.codecRegistry());
        ServiceSupport serviceSupport = ServiceSupport.bukkit(this);

        QueryStringParser parser = new QueryStringParser(apiImpl, config);
        ResultRenderer renderer = new ResultRenderer(config);
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
        SpyglassSuggestions suggestions = new SpyglassSuggestions(apiImpl);

        V1ToV2Translator translator = new V1ToV2Translator(
                V1ItemDecoder.bukkit(), WorldNameLookup.bukkit(), getLogger());
        MigrationService migrationService = new MigrationService(recordStore, config, translator, getLogger());
        MigrationCommand migrationCommand = new MigrationCommand(migrationService, getLogger());

        SpyglassCommands commands = new SpyglassCommands(
                this,
                helpService,
                eventsService,
                searchService,
                rollbackService,
                undoService,
                pageService,
                toolService,
                suggestions,
                migrationCommand);
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

        getLogger().info("Spyglass enabled; events=" + enabledEvents);
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
