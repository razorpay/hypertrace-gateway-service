package org.hypertrace.gateway.service.trace;

import com.codahale.metrics.Timer;
import com.codahale.metrics.Timer.Context;
import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.hypertrace.core.attribute.service.v1.AttributeMetadata;
import org.hypertrace.core.attribute.service.v1.AttributeScope;
import org.hypertrace.core.query.service.api.ColumnMetadata;
import org.hypertrace.core.query.service.api.Filter;
import org.hypertrace.core.query.service.api.QueryRequest;
import org.hypertrace.core.query.service.api.QueryRequest.Builder;
import org.hypertrace.core.query.service.api.ResultSetChunk;
import org.hypertrace.core.query.service.api.Row;
import org.hypertrace.core.query.service.client.QueryServiceClient;
import org.hypertrace.core.query.service.util.QueryRequestUtil;
import org.hypertrace.core.serviceframework.metrics.PlatformMetricsRegistry;
import org.hypertrace.gateway.service.common.AttributeMetadataProvider;
import org.hypertrace.gateway.service.common.RequestContext;
import org.hypertrace.gateway.service.common.config.ScopeFilterConfigs;
import org.hypertrace.gateway.service.common.converters.QueryAndGatewayDtoConverter;
import org.hypertrace.gateway.service.common.transformer.RequestPreProcessor;
import org.hypertrace.gateway.service.common.transformer.ResponsePostProcessor;
import org.hypertrace.gateway.service.v1.common.OrderByExpression;
import org.hypertrace.gateway.service.v1.trace.Trace;
import org.hypertrace.gateway.service.v1.trace.TracesRequest;
import org.hypertrace.gateway.service.v1.trace.TracesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service to aggregate and create Api Trace.
 *
 * <p>Api Trace = 1 Api Execution that contains 1 entry span + multiple correlated exit spans The
 * entry span is called root span.
 *
 * <p>The trace attribute = root span attributes, and the trace is filterable by the root span's
 * attributes.
 *
 * <p>Trace will not have independent attributes.
 */
public class TracesService {

  private static final Logger LOG = LoggerFactory.getLogger(TracesService.class);
  private static final String START_TIMESTAMP_KEY_NAME = "startTime";

  private final QueryServiceClient queryServiceClient;
  private final AttributeMetadataProvider attributeMetadataProvider;
  private final TracesRequestValidator requestValidator;
  private final RequestPreProcessor requestPreProcessor;
  private final ResponsePostProcessor responsePostProcessor;
  private final ScopeFilterConfigs scopeFilterConfigs;

  private Timer queryExecutionTimer;

  public TracesService(
      QueryServiceClient queryServiceClient,
      AttributeMetadataProvider attributeMetadataProvider,
      ScopeFilterConfigs scopeFilterConfigs) {
    this.queryServiceClient = queryServiceClient;
    this.attributeMetadataProvider = attributeMetadataProvider;
    this.requestValidator = new TracesRequestValidator();
    this.requestPreProcessor = new RequestPreProcessor(attributeMetadataProvider);
    this.responsePostProcessor = new ResponsePostProcessor(attributeMetadataProvider);
    this.scopeFilterConfigs = scopeFilterConfigs;
    initMetrics();
  }

  private void initMetrics() {
    queryExecutionTimer = new Timer();
    PlatformMetricsRegistry.register("traces.query.execution", queryExecutionTimer);
  }

  public TracesResponse getTracesByFilter(RequestContext context, TracesRequest request) {
    final Context timerContext = queryExecutionTimer.time();
    try {
      TracesRequest preProcessedRequest = requestPreProcessor.transform(request, context);

      requestValidator.validateScope(preProcessedRequest);
      TraceScope scope = TraceScope.valueOf(preProcessedRequest.getScope());

      AttributeScope attributeScope = TraceScopeConverter.toAttributeScope(scope);
      Map<String, AttributeMetadata> attributeMap =
          attributeMetadataProvider.getAttributesMetadata(context, attributeScope);

      requestValidator.validate(preProcessedRequest, attributeMap);

      // Add extra filters based on the scope
      preProcessedRequest =
          TracesRequest.newBuilder(preProcessedRequest)
              .setFilter(
                  scopeFilterConfigs.createScopeFilter(
                      attributeScope,
                      preProcessedRequest.getFilter(),
                      attributeMetadataProvider,
                      context))
              .build();

      TracesResponse.Builder tracesResponseBuilder = TracesResponse.newBuilder();
      // filter traces

      Collection<Trace> filteredTraces =
          filterTraces(context, preProcessedRequest, attributeMap, scope);
      tracesResponseBuilder.addAllTraces(filteredTraces);
      // Get the total API Traces in a separate query because this will scale better
      // for large data-set
      tracesResponseBuilder.setTotal(getTotalFilteredTraces(context, preProcessedRequest, scope));

      TracesResponse.Builder postProcessedResponse =
          responsePostProcessor.transform(request, context, tracesResponseBuilder);
      TracesResponse response = postProcessedResponse.build();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Traces Service Response: {}", response);
      }
      return response;
    } finally {
      timerContext.stop();
    }
  }

  @VisibleForTesting
  List<Trace> filterTraces(
      RequestContext context,
      TracesRequest request,
      Map<String, AttributeMetadata> attributeMetadataMap,
      TraceScope scope) {

    QueryRequest.Builder builder = createQueryWithFilter(request, scope, context);

    if (!request.getSelectionList().isEmpty()) {
      request
          .getSelectionList()
          .forEach(
              exp ->
                  builder.addSelection(QueryAndGatewayDtoConverter.convertToQueryExpression(exp)));
    }

    // Adds the parent span id selection to the query builder for the span event
    addSortLimitAndOffset(request, builder);

    List<Trace> tracesResult = new ArrayList<>();
    QueryRequest queryRequest = builder.build();
    Iterator<ResultSetChunk> resultSetChunkIterator =
        queryServiceClient.executeQuery(queryRequest, context.getHeaders(), 5000);

    // form the result
    while (resultSetChunkIterator.hasNext()) {
      ResultSetChunk chunk = resultSetChunkIterator.next();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Received chunk: " + chunk.toString());
      }

      if (chunk.getRowCount() < 1) {
        break;
      }

      for (Row row : chunk.getRowList()) {
        Trace.Builder traceBuilder = Trace.newBuilder();
        for (int i = 0; i < chunk.getResultSetMetadata().getColumnMetadataCount(); i++) {
          ColumnMetadata metadata = chunk.getResultSetMetadata().getColumnMetadata(i);
          String attrName = metadata.getColumnName();
          traceBuilder.putAttributes(
              metadata.getColumnName(),
              QueryAndGatewayDtoConverter.convertToGatewayValue(
                  attrName, row.getColumn(i), attributeMetadataMap));
        }

        tracesResult.add(traceBuilder.build());
      }
    }
    return tracesResult;
  }

  int getTotalFilteredTraces(RequestContext context, TracesRequest request, TraceScope scope) {
    int total = 0;
    Builder queryBuilder = createQueryWithFilter(request, scope, context);
    // validated that the selection is not empty
    if (request.getSelectionCount() < 1) {
      throw new IllegalArgumentException("Query request does not have any selection");
    }

    String columnName = request.getSelection(0).getColumnIdentifier().getColumnName();
    queryBuilder.addSelection(QueryRequestUtil.createCountByColumnSelection(columnName));
    QueryRequest queryRequest = queryBuilder.build();
    Iterator<ResultSetChunk> resultSetChunkIterator =
        queryServiceClient.executeQuery(queryRequest, context.getHeaders(), 5000);
    while (resultSetChunkIterator.hasNext()) {
      ResultSetChunk chunk = resultSetChunkIterator.next();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Received chunk: " + chunk.toString());
      }

      // There should be only 1 result
      if (chunk.getRowCount() != 1 && chunk.getResultSetMetadata().getColumnMetadataCount() != 1) {
        LOG.error(
            "Count the Api Traces total returned in multiple row / column. "
                + "Total Row: {}, Total Column: {}",
            chunk.getRowCount(),
            chunk.getResultSetMetadata().getColumnMetadataCount());
        break;
      }

      // There's only 1 result with 1 column. If there's no result, Pinot doesn't
      // return any row unfortunately
      if (chunk.getRowCount() > 0) {
        Row row = chunk.getRow(0);
        String totalStr = row.getColumn(0).getString();
        try {
          total = Integer.parseInt(totalStr);
        } catch (NumberFormatException nfe) {
          LOG.error(
              "Unable to convert Total to a number. Received value: {} from Query Service",
              totalStr);
        }
      }
    }
    return total;
  }

  private Builder createQueryWithFilter(
      TracesRequest request, TraceScope scope, RequestContext requestContext) {
    AttributeScope attributeScope = TraceScopeConverter.toAttributeScope(scope);
    Builder queryBuilder = QueryRequest.newBuilder();

    Filter.Builder filterBuilder =
        QueryAndGatewayDtoConverter.addTimeFilterAndConvertToQueryFilter(
            request.getStartTimeMillis(),
            request.getEndTimeMillis(),
            attributeMetadataProvider
                .getAttributeMetadata(requestContext, attributeScope, START_TIMESTAMP_KEY_NAME)
                .get()
                .getId(),
            request.getFilter());
    queryBuilder.setFilter(filterBuilder);
    return queryBuilder;
  }

  // Adds the sort, limit and offset information to the QueryService if it is requested
  private void addSortLimitAndOffset(TracesRequest request, Builder queryBuilder) {
    if (request.getOrderByCount() > 0) {
      List<OrderByExpression> orderByExpressions = request.getOrderByList();
      queryBuilder.addAllOrderBy(
          QueryAndGatewayDtoConverter.convertToQueryOrderByExpressions(orderByExpressions));
    }

    int limit = request.getLimit();
    if (limit > 0) {
      queryBuilder.setLimit(limit);
    }

    int offset = request.getOffset();
    if (offset > 0) {
      queryBuilder.setOffset(offset);
    }
  }
}