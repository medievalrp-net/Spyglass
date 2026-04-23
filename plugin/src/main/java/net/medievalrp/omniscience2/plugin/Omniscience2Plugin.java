package net.medievalrp.omniscience2.plugin;

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
import net.medievalrp.omniscience2.plugin.command.param.EntityParam;
import net.medievalrp.omniscience2.plugin.command.param.EventParam;
import net.medievalrp.omniscience2.plugin.command.param.PlayerParam;
import net.medievalrp.omniscience2.plugin.command.param.QueryStringParser;
import net.medievalrp.omniscience2.plugin.command.param.RadiusParam;
import net.medievalrp.omniscience2.plugin.command.param.TimeParam;
import net.medievalrp.omniscience2.plugin.command.param.WorldParam;
import net.medievalrp.omniscience2.plugin.command.render.ResultRenderer;
import net.medievalrp.omniscience2.plugin.command.service.EventsService;
import net.medievalrp.omniscience2.plugin.command.service.HelpService;
import net.medievalrp.omniscience2.plugin.command.service.PageService;
import net.medievalrp.omniscience2.plugin.command.service.RollbackService;
import net.medievalrp.omniscience2.plugin.command.service.SearchService;
import net.medievalrp.omniscience2.plugin.command.service.ServiceSupport;
import net.medievalrp.omniscience2.plugin.command.service.ToolService;
import net.medievalrp.omniscience2.plugin.command.service.UndoService;
import net.medievalrp.omniscience2.plugin.config.Omniscience2Config;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.listener.block.BlockBreakExtractor;
import net.medievalrp.omniscience2.plugin.listener.block.BlockPlaceExtractor;
import net.medievalrp.omniscience2.plugin.listener.chat.ChatExtractor;
import net.medievalrp.omniscience2.plugin.listener.chat.CommandExtractor;
import net.medievalrp.omniscience2.plugin.listener.container.ContainerTransactionExtractor;
import net.medievalrp.omniscience2.plugin.listener.environment.BlockFadeExtractor;
import net.medievalrp.omniscience2.plugin.listener.environment.BlockFormExtractor;
import net.medievalrp.omniscience2.plugin.listener.environment.BlockGrowExtractor;
import net.medievalrp.omniscience2.plugin.listener.environment.BlockIgniteExtractor;
import net.medievalrp.omniscience2.plugin.listener.environment.LeavesDecayExtractor;
import net.medievalrp.omniscience2.plugin.listener.environment.StructureGrowExtractor;
import net.medievalrp.omniscience2.plugin.listener.player.JoinExtractor;
import net.medievalrp.omniscience2.plugin.listener.player.QuitExtractor;
import net.medievalrp.omniscience2.plugin.pipeline.AsyncRecorder;
import net.medievalrp.omniscience2.plugin.pipeline.ExtractorRegistry;
import net.medievalrp.omniscience2.plugin.rollback.RollbackEngine;
import net.medievalrp.omniscience2.plugin.rollback.UndoStack;
import net.medievalrp.omniscience2.plugin.storage.IndexManager;
import net.medievalrp.omniscience2.plugin.storage.MongoRecordStore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class Omniscience2Plugin extends JavaPlugin {

    private AsyncRecorder recorder;
    private MongoRecordStore recordStore;
    private Executor queryExecutor;
    private Omniscience2Config config;

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

        ExtractorSupport support = new ExtractorSupport(config.storage().retention());
        ExtractorRegistry registry = new ExtractorRegistry(recorder);
        if (enabledEvents.contains("break")) registry.register(this, new BlockBreakExtractor(support));
        if (enabledEvents.contains("place")) registry.register(this, new BlockPlaceExtractor(support));
        if (enabledEvents.contains("deposit") || enabledEvents.contains("withdraw"))
            registry.register(this, new ContainerTransactionExtractor(support));
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

        Omniscience2ApiImpl apiImpl = new Omniscience2ApiImpl(recorder, recordStore, queryExecutor, enabledEvents);
        apiImpl.registerQueryParamHandler(new PlayerParam());
        apiImpl.registerQueryParamHandler(new EventParam(enabledEvents));
        apiImpl.registerQueryParamHandler(new RadiusParam());
        apiImpl.registerQueryParamHandler(new TimeParam());
        apiImpl.registerQueryParamHandler(new BlockParam());
        apiImpl.registerQueryParamHandler(new EntityParam());
        apiImpl.registerQueryParamHandler(new WorldParam());

        Bukkit.getServicesManager().register(Omniscience2Api.class, apiImpl, this, ServicePriority.Normal);

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
        ToolService toolService = new ToolService();
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
                suggestions);
        commands.register();

        getLogger().info("Omniscience2 enabled; events=" + enabledEvents);
    }

    @Override
    public void onDisable() {
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
