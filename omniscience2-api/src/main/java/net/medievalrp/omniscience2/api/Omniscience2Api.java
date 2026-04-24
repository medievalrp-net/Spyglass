package net.medievalrp.omniscience2.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.extension.DisplayRenderer;
import net.medievalrp.omniscience2.api.param.QueryParamHandler;
import net.medievalrp.omniscience2.api.query.QueryRequest;
import net.medievalrp.omniscience2.api.query.QueryResult;

public interface Omniscience2Api {

    void record(EventRecord record);

    CompletionStage<QueryResult> query(QueryRequest request);

    void registerQueryParamHandler(QueryParamHandler handler);

    Optional<QueryParamHandler> queryParam(String alias);

    List<QueryParamHandler> queryParams();

    void registerDisplayRenderer(String eventName, DisplayRenderer renderer);

    Optional<DisplayRenderer> displayRenderer(String eventName);

    Set<String> enabledEvents();
}
