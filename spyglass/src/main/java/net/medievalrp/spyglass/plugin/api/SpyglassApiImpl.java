package net.medievalrp.spyglass.plugin.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.SpyglassLimits;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.extension.DisplayRenderer;
import net.medievalrp.spyglass.api.extension.FlagHandler;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.rollback.RollbackEffectHandler;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class SpyglassApiImpl implements SpyglassApi {

    private final Recorder recorder;
    private final RecordStore recordStore;
    private final Executor queryExecutor;
    private final Map<String, QueryParamHandler> params = new LinkedHashMap<>();
    private final Map<String, FlagHandler> flagHandlers = new LinkedHashMap<>();
    private final Map<String, DisplayRenderer> renderers = new LinkedHashMap<>();
    private final Map<String, RollbackEffectHandler> effectHandlers = new LinkedHashMap<>();
    private final Set<String> enabledEvents;
    private final SpyglassLimits limits;
    private final String serverName;
    private final Logger logger;

    public SpyglassApiImpl(Recorder recorder,
                               RecordStore recordStore,
                               Executor queryExecutor,
                               Set<String> enabledEvents,
                               SpyglassLimits limits,
                               String serverName,
                               Logger logger) {
        this.recorder = recorder;
        this.recordStore = recordStore;
        this.queryExecutor = queryExecutor;
        this.enabledEvents = Set.copyOf(enabledEvents);
        this.limits = limits;
        this.serverName = serverName;
        this.logger = logger;
    }

    @Override
    public void record(EventRecord record) {
        recorder.record(record);
    }

    @Override
    public CompletionStage<QueryResult> query(QueryRequest request) {
        return CompletableFuture.supplyAsync(() -> recordStore.query(request), queryExecutor);
    }

    /**
     * Internal display-fast-path: skips the heavy snapshot fields
     * (originalBlock, newBlock, item payloads). The search renderer never
     * looks at those, and leaving them unhydrated drops the per-record
     * allocation cost by an order of magnitude on block-event pages.
     *
     * <p>Not on the public {@link SpyglassApi} interface — extensions
     * that query the API get full records. The plugin's own search
     * service uses this directly.
     */
    @ApiStatus.Internal
    public CompletionStage<QueryResult> querySummary(QueryRequest request) {
        return CompletableFuture.supplyAsync(() -> recordStore.querySummary(request), queryExecutor);
    }

    @Override
    public void registerQueryParamHandler(QueryParamHandler handler) {
        handler.aliases().forEach(alias -> params.put(alias.toLowerCase(), handler));
    }

    @Override
    public Optional<QueryParamHandler> queryParam(String alias) {
        return Optional.ofNullable(params.get(alias.toLowerCase()));
    }

    @Override
    public List<QueryParamHandler> queryParams() {
        return new ArrayList<>(new LinkedHashSet<>(params.values()));
    }

    @Override
    public void registerFlagHandler(FlagHandler handler) {
        handler.aliases().forEach(alias -> flagHandlers.put(alias.toLowerCase(), handler));
    }

    @Override
    public Optional<FlagHandler> flag(String alias) {
        return Optional.ofNullable(flagHandlers.get(alias.toLowerCase()));
    }

    @Override
    public List<FlagHandler> flags() {
        return new ArrayList<>(new LinkedHashSet<>(flagHandlers.values()));
    }

    @Override
    public void registerDisplayRenderer(String eventName, DisplayRenderer renderer) {
        renderers.put(eventName.toLowerCase(), renderer);
    }

    @Override
    public Optional<DisplayRenderer> displayRenderer(String eventName) {
        return Optional.ofNullable(renderers.get(eventName.toLowerCase()));
    }

    @Override
    public void registerRollbackEffectHandler(RollbackEffectHandler handler) {
        effectHandlers.put(handler.type().toLowerCase(), handler);
    }

    @Override
    public Optional<RollbackEffectHandler> rollbackEffectHandler(String type) {
        return type == null ? Optional.empty() : Optional.ofNullable(effectHandlers.get(type.toLowerCase()));
    }

    @Override
    public Set<String> enabledEvents() {
        return enabledEvents;
    }

    @Override
    public SpyglassLimits limits() {
        return limits;
    }

    @Override
    public String serverName() {
        return serverName;
    }

    @Override
    public Logger logger() {
        return logger;
    }
}
