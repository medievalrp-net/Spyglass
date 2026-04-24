package net.medievalrp.spyglass.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.extension.DisplayRenderer;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;

public interface SpyglassApi {

    void record(EventRecord record);

    CompletionStage<QueryResult> query(QueryRequest request);

    void registerQueryParamHandler(QueryParamHandler handler);

    Optional<QueryParamHandler> queryParam(String alias);

    List<QueryParamHandler> queryParams();

    void registerDisplayRenderer(String eventName, DisplayRenderer renderer);

    Optional<DisplayRenderer> displayRenderer(String eventName);

    Set<String> enabledEvents();
}
