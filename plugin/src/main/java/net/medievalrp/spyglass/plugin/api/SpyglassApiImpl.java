package net.medievalrp.spyglass.plugin.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.extension.DisplayRenderer;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class SpyglassApiImpl implements SpyglassApi {

    private final Recorder recorder;
    private final RecordStore recordStore;
    private final Executor queryExecutor;
    private final Map<String, QueryParamHandler> params = new LinkedHashMap<>();
    private final Map<String, DisplayRenderer> renderers = new LinkedHashMap<>();
    private final Set<String> enabledEvents;

    public SpyglassApiImpl(Recorder recorder, RecordStore recordStore, Executor queryExecutor, Set<String> enabledEvents) {
        this.recorder = recorder;
        this.recordStore = recordStore;
        this.queryExecutor = queryExecutor;
        this.enabledEvents = Set.copyOf(enabledEvents);
    }

    @Override
    public void record(EventRecord record) {
        recorder.record(record);
    }

    @Override
    public CompletionStage<QueryResult> query(QueryRequest request) {
        return CompletableFuture.supplyAsync(() -> recordStore.query(request), queryExecutor);
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
        return new ArrayList<>(new java.util.LinkedHashSet<>(params.values()));
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
    public Set<String> enabledEvents() {
        return enabledEvents;
    }
}
