package org.hypertrace.gateway.service.common.datafetcher;

import static org.hypertrace.gateway.service.common.converters.QueryAndGatewayDtoConverter.convertToQueryExpression;

import com.google.common.base.Preconditions;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.lang3.StringUtils;
import org.hypertrace.core.attribute.service.v1.AttributeMetadata;
import org.hypertrace.core.attribute.service.v1.AttributeScope;
import org.hypertrace.core.query.service.api.ColumnMetadata;
import org.hypertrace.core.query.service.api.Expression;
import org.hypertrace.core.query.service.api.Filter;
import org.hypertrace.core.query.service.api.Operator;
import org.hypertrace.core.query.service.api.QueryRequest;
import org.hypertrace.core.query.service.api.ResultSetChunk;
import org.hypertrace.core.query.service.api.Row;
import org.hypertrace.core.query.service.client.QueryServiceClient;
import org.hypertrace.core.query.service.util.QueryRequestUtil;
import org.hypertrace.gateway.service.common.AttributeMetadataProvider;
import org.hypertrace.gateway.service.common.converters.QueryAndGatewayDtoConverter;
import org.hypertrace.gateway.service.common.util.ArithmeticValueUtil;
import org.hypertrace.gateway.service.common.util.AttributeMetadataUtil;
import org.hypertrace.gateway.service.common.util.ExpressionReader;
import org.hypertrace.gateway.service.common.util.MetricAggregationFunctionUtil;
import org.hypertrace.gateway.service.common.util.QueryExpressionUtil;
import org.hypertrace.gateway.service.entity.EntitiesRequestContext;
import org.hypertrace.gateway.service.entity.EntitiesRequestValidator;
import org.hypertrace.gateway.service.entity.EntityKey;
import org.hypertrace.gateway.service.v1.common.AggregatedMetricValue;
import org.hypertrace.gateway.service.v1.common.Expression.ValueCase;
import org.hypertrace.gateway.service.v1.common.FunctionType;
import org.hypertrace.gateway.service.v1.common.Health;
import org.hypertrace.gateway.service.v1.common.Interval;
import org.hypertrace.gateway.service.v1.common.MetricSeries;
import org.hypertrace.gateway.service.v1.common.Period;
import org.hypertrace.gateway.service.v1.common.TimeAggregation;
import org.hypertrace.gateway.service.v1.common.Value;
import org.hypertrace.gateway.service.v1.common.ValueType;
import org.hypertrace.gateway.service.v1.entity.EntitiesRequest;
import org.hypertrace.gateway.service.v1.entity.Entity;
import org.hypertrace.gateway.service.v1.entity.Entity.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of the {@link IEntityFetcher} using the QueryService as the data source
 */
public class QueryServiceEntityFetcher implements IEntityFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(QueryServiceEntityFetcher.class);
  private static final String COUNT_COLUMN_NAME = "Count";
  private static final String QUERY_SERVICE_NULL = "null";

  private final EntitiesRequestValidator entitiesRequestValidator = new EntitiesRequestValidator();
  private final QueryServiceClient queryServiceClient;
  private final AttributeMetadataProvider attributeMetadataProvider;

  public QueryServiceEntityFetcher(
      QueryServiceClient queryServiceClient, AttributeMetadataProvider attributeMetadataProvider) {
    this.queryServiceClient = queryServiceClient;
    this.attributeMetadataProvider = attributeMetadataProvider;
  }

  @Override
  public EntityFetcherResponse getEntities(
      EntitiesRequestContext requestContext, EntitiesRequest entitiesRequest) {
    List<String> entityIdAttributes =
        AttributeMetadataUtil.getIdAttributeIds(
            attributeMetadataProvider, requestContext, entitiesRequest.getEntityType());
    List<Expression> idExpressions =
        entityIdAttributes.stream()
            .map(QueryRequestUtil::createColumnExpression)
            .map(Expression.Builder::build)
            .collect(Collectors.toList());

    // Validate EntitiesRequest
    entitiesRequestValidator.validate(
        entitiesRequest,
        attributeMetadataProvider.getAttributesMetadata(
            requestContext, AttributeScope.valueOf(entitiesRequest.getEntityType())));

    Filter.Builder filterBuilder =
        constructQueryServiceFilter(entitiesRequest, requestContext, entityIdAttributes);

    QueryRequest.Builder builder =
        QueryRequest.newBuilder()
            .setFilter(filterBuilder)
            // Add EntityID attributes as the first selection and group by
            .addAllSelection(idExpressions)
            .addAllGroupBy(idExpressions);

    // Add all expressions in the select/group that are already not part of the EntityID attributes
    entitiesRequest.getSelectionList().stream()
        .filter(expression -> expression.getValueCase() == ValueCase.COLUMNIDENTIFIER)
        .filter(
            expression ->
                !entityIdAttributes.contains(expression.getColumnIdentifier().getColumnName()))
        .forEach(
            expression -> {
              Expression.Builder expBuilder = convertToQueryExpression(expression);
              builder.addSelection(expBuilder);
              builder.addGroupBy(expBuilder);
            });

    // Pinot's GroupBy queries need at least one aggregate operation in the selection
    // so we add count(*) as a dummy placeholder.
    builder.addSelection(
        QueryRequestUtil.createCountByColumnSelection(entityIdAttributes.toArray(new String[]{})));

    // Pinot truncates the GroupBy results to 10 when there is no limit explicitly but
    // here we neither want the results to be truncated nor apply the limit coming from client.
    // We would like to get all entities based on filters so we set the limit to a high value.
    builder.setLimit(QueryServiceClient.DEFAULT_QUERY_SERVICE_GROUP_BY_LIMIT);

    QueryRequest queryRequest = builder.build();

    if (LOG.isDebugEnabled()) {
      LOG.debug("Sending Query to Query Service ======== \n {}", queryRequest);
    }

    Iterator<ResultSetChunk> resultSetChunkIterator =
        queryServiceClient.executeQuery(queryRequest, requestContext.getHeaders(), 5000);

    // We want to retain the order as returned from the respective source. Hence using a
    // LinkedHashMap
    Map<EntityKey, Entity.Builder> entityBuilders = new LinkedHashMap<>();
    while (resultSetChunkIterator.hasNext()) {
      ResultSetChunk chunk = resultSetChunkIterator.next();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Received chunk: " + chunk.toString());
      }

      if (chunk.getRowCount() < 1) {
        break;
      }

      for (Row row : chunk.getRowList()) {
        // Construct the entity id from the entityIdAttributes columns
        EntityKey entityKey =
            EntityKey.of(
                IntStream.range(0, entityIdAttributes.size())
                    .mapToObj(value -> row.getColumn(value).getString())
                    .toArray(String[]::new));
        Builder entityBuilder = entityBuilders.computeIfAbsent(entityKey, k -> Entity.newBuilder());
        entityBuilder.setEntityType(entitiesRequest.getEntityType());

        // Always include the id in entity since that's needed to make follow up queries in
        // optimal fashion. If this wasn't really requested by the client, it should be removed
        // as post processing.
        for (int i = 0; i < entityIdAttributes.size(); i++) {
          entityBuilder.putAttribute(
              entityIdAttributes.get(i),
              org.hypertrace.gateway.service.v1.common.Value.newBuilder()
                  .setString(entityKey.getAttributes().get(i))
                  .setValueType(org.hypertrace.gateway.service.v1.common.ValueType.STRING)
                  .build());
        }

        for (int i = entityIdAttributes.size();
            i < chunk.getResultSetMetadata().getColumnMetadataCount();
            i++) {
          ColumnMetadata metadata = chunk.getResultSetMetadata().getColumnMetadata(i);

          // Ignore the count column since we introduced that ourselves into the query.
          if (StringUtils.equals(COUNT_COLUMN_NAME, metadata.getColumnName())) {
            continue;
          }

          String attributeName = metadata.getColumnName();
          entityBuilder.putAttribute(
              attributeName,
              QueryAndGatewayDtoConverter.convertToGatewayValue(
                  attributeName,
                  row.getColumn(i),
                  attributeMetadataProvider.getAttributesMetadata(
                      requestContext, AttributeScope.valueOf(entitiesRequest.getEntityType()))));
        }
      }
    }

    return new EntityFetcherResponse(entityBuilders);
  }

  @Override
  public EntityFetcherResponse getAggregatedMetrics(
      EntitiesRequestContext requestContext, EntitiesRequest entitiesRequest) {
    // Only supported filter is entityIds IN ["id1", "id2", "id3"]
    Map<String, AttributeMetadata> attributeMetadataMap =
        attributeMetadataProvider.getAttributesMetadata(
            requestContext, AttributeScope.valueOf(entitiesRequest.getEntityType()));
    entitiesRequestValidator.validate(entitiesRequest, attributeMetadataMap);

    List<org.hypertrace.gateway.service.v1.common.Expression> aggregates =
        ExpressionReader.getFunctionExpressions(entitiesRequest.getSelectionList().stream());
    if (aggregates.isEmpty()) {
      return new EntityFetcherResponse();
    }

    List<String> entityIdAttributes =
        AttributeMetadataUtil.getIdAttributeIds(
            attributeMetadataProvider, requestContext, entitiesRequest.getEntityType());
    List<Expression> idExpressions =
        entityIdAttributes.stream()
            .map(QueryRequestUtil::createColumnExpression)
            .map(Expression.Builder::build)
            .collect(Collectors.toList());

    QueryRequest.Builder builder = QueryRequest.newBuilder();

    for (org.hypertrace.gateway.service.v1.common.Expression aggregate : aggregates) {
      requestContext.mapAliasToFunctionExpression(
          aggregate.getFunction().getAlias(), aggregate.getFunction());
      builder.addSelection(QueryAndGatewayDtoConverter.convertToQueryExpression(aggregate));
    }

    Filter.Builder filterBuilder =
        constructQueryServiceFilter(entitiesRequest, requestContext, entityIdAttributes);
    builder.setFilter(filterBuilder);
    builder.addAllGroupBy(idExpressions);

    // Pinot truncates the GroupBy results to 10 when there is no limit explicitly but
    // here we neither want the results to be truncated nor apply the limit coming from client.
    // We would like to get all entities based on filters so we set the limit to a high value.
    builder.setLimit(QueryServiceClient.DEFAULT_QUERY_SERVICE_GROUP_BY_LIMIT);

    QueryRequest request = builder.build();

    if (LOG.isDebugEnabled()) {
      LOG.debug("Sending Aggregated Metrics Request to Query Service ======== \n {}", request);
    }

    Iterator<ResultSetChunk> resultSetChunkIterator =
        queryServiceClient.executeQuery(request, requestContext.getHeaders(), 5000);

    // We want to retain the order as returned from the respective source. Hence using a
    // LinkedHashMap
    Map<EntityKey, Builder> entityMap = new LinkedHashMap<>();

    while (resultSetChunkIterator.hasNext()) {
      ResultSetChunk chunk = resultSetChunkIterator.next();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Received chunk: " + chunk.toString());
      }

      if (chunk.getRowCount() < 1) {
        break;
      }

      if (!chunk.hasResultSetMetadata()) {
        LOG.warn("Chunk doesn't have result metadata so couldn't process the response.");
        break;
      }

      for (Row row : chunk.getRowList()) {
        // Construct the EntityKey from the EntityId attributes columns
        EntityKey entityKey =
            EntityKey.of(
                IntStream.range(0, entityIdAttributes.size())
                    .mapToObj(value -> row.getColumn(value).getString())
                    .toArray(String[]::new));
        Builder entityBuilder = entityMap.computeIfAbsent(entityKey, k -> Entity.newBuilder());
        entityBuilder.setEntityType(entitiesRequest.getEntityType());

        // Always include the id in entity since that's needed to make follow up queries in
        // optimal fashion. If this wasn't really requested by the client, it should be removed
        // as post processing.
        for (int i = 0; i < entityIdAttributes.size(); i++) {
          entityBuilder.putAttribute(
              entityIdAttributes.get(i),
              org.hypertrace.gateway.service.v1.common.Value.newBuilder()
                  .setString(entityKey.getAttributes().get(i))
                  .setValueType(org.hypertrace.gateway.service.v1.common.ValueType.STRING)
                  .build());
        }

        for (int i = entityIdAttributes.size();
            i < chunk.getResultSetMetadata().getColumnMetadataCount();
            i++) {
          ColumnMetadata metadata = chunk.getResultSetMetadata().getColumnMetadata(i);
          org.hypertrace.gateway.service.v1.common.FunctionExpression function =
              requestContext.getFunctionExpressionByAlias(metadata.getColumnName());
          List<org.hypertrace.gateway.service.v1.common.Expression> healthExpressions =
              function.getArgumentsList().stream()
                  .filter(org.hypertrace.gateway.service.v1.common.Expression::hasHealth)
                  .collect(Collectors.toList());
          Preconditions.checkArgument(healthExpressions.size() <= 1);
          Health health = Health.NOT_COMPUTED;

          if (FunctionType.AVGRATE == function.getFunction()) {
            Value avgRateValue =
                ArithmeticValueUtil.computeAvgRate(
                    function,
                    row.getColumn(i),
                    entitiesRequest.getStartTimeMillis(),
                    entitiesRequest.getEndTimeMillis());

            entityBuilder.putMetric(
                metadata.getColumnName(),
                AggregatedMetricValue.newBuilder()
                    .setValue(avgRateValue)
                    .setFunction(function.getFunction())
                    .setHealth(health)
                    .build());
          } else {
            org.hypertrace.core.query.service.api.Value queryValue = row.getColumn(i);
            Value gwValue =
                QueryAndGatewayDtoConverter.convertToGatewayValueForMetricValue(
                    MetricAggregationFunctionUtil.getValueTypeFromFunction(
                        function, attributeMetadataMap),
                    attributeMetadataMap,
                    metadata,
                    queryValue);
            entityBuilder.putMetric(
                metadata.getColumnName(),
                AggregatedMetricValue.newBuilder()
                    .setValue(gwValue)
                    .setFunction(function.getFunction())
                    .setHealth(health)
                    .build());
          }
        }
      }
    }
    return new EntityFetcherResponse(entityMap);
  }

  @Override
  public EntityFetcherResponse getTimeAggregatedMetrics(
      EntitiesRequestContext requestContext, EntitiesRequest entitiesRequest) {
    // Only supported filter is entityIds IN ["id1", "id2", "id3"]
    List<String> idColumns =
        AttributeMetadataUtil.getIdAttributeIds(
            attributeMetadataProvider, requestContext, entitiesRequest.getEntityType());
    String timeColumn =
        AttributeMetadataUtil.getTimestampAttributeId(
            attributeMetadataProvider, requestContext, entitiesRequest.getEntityType());
    Map<String, AttributeMetadata> attributeMetadataMap =
        attributeMetadataProvider.getAttributesMetadata(
            requestContext, AttributeScope.valueOf(entitiesRequest.getEntityType()));

    entitiesRequestValidator.validate(entitiesRequest, attributeMetadataMap);

    entitiesRequest
        .getTimeAggregationList()
        .forEach(
            (timeAggregation ->
                requestContext.mapAliasToTimeAggregation(
                    timeAggregation
                        .getAggregation()
                        .getFunction()
                        .getAlias(), // Required to be set by the validators
                    timeAggregation)));

    // First group the Aggregations based on the period so that we can issue separate queries
    // to QueryService for each different Period.
    Collection<List<TimeAggregation>> result =
        entitiesRequest.getTimeAggregationList().stream()
            .collect(Collectors.groupingBy(TimeAggregation::getPeriod))
            .values();

    Map<EntityKey, Map<String, MetricSeries.Builder>> entityMetricSeriesMap = new LinkedHashMap<>();
    for (List<TimeAggregation> batch : result) {
      Period period = batch.get(0).getPeriod();
      ChronoUnit unit = ChronoUnit.valueOf(period.getUnit());
      long periodSecs = Duration.of(period.getValue(), unit).getSeconds();
      QueryRequest request =
          buildTimeSeriesQueryRequest(
              entitiesRequest, requestContext, periodSecs, batch, idColumns, timeColumn);

      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "Sending time series queryRequest to query service: ======== \n {}",
            request.toString());
      }

      Iterator<ResultSetChunk> resultSetChunkIterator =
          queryServiceClient.executeQuery(request, requestContext.getHeaders(), 5000);

      while (resultSetChunkIterator.hasNext()) {
        ResultSetChunk chunk = resultSetChunkIterator.next();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Received chunk: " + chunk.toString());
        }

        if (chunk.getRowCount() < 1) {
          break;
        }

        if (!chunk.hasResultSetMetadata()) {
          LOG.warn("Chunk doesn't have result metadata so couldn't process the response.");
          break;
        }

        for (Row row : chunk.getRowList()) {
          // Construct the entity id from the entityIdAttributes columns
          EntityKey entityKey =
              EntityKey.of(
                  IntStream.range(0, idColumns.size())
                      .mapToObj(value -> row.getColumn(value).getString())
                      .toArray(String[]::new));

          Map<String, MetricSeries.Builder> metricSeriesMap =
              entityMetricSeriesMap.computeIfAbsent(entityKey, k -> new LinkedHashMap<>());

          Interval.Builder intervalBuilder = Interval.newBuilder();

          // Second column is the time column
          Value value =
              QueryAndGatewayDtoConverter.convertQueryValueToGatewayValue(
                  row.getColumn(idColumns.size()));
          if (value.getValueType() == ValueType.STRING) {
            long time = Long.parseLong(value.getString());
            intervalBuilder.setStartTimeMillis(time);
            intervalBuilder.setEndTimeMillis(time + TimeUnit.SECONDS.toMillis(periodSecs));

            for (int i = idColumns.size() + 1;
                i < chunk.getResultSetMetadata().getColumnMetadataCount();
                i++) {
              ColumnMetadata metadata = chunk.getResultSetMetadata().getColumnMetadata(i);
              org.hypertrace.gateway.service.v1.common.TimeAggregation timeAggregation =
                  requestContext.getTimeAggregationByAlias(metadata.getColumnName());

              if (timeAggregation == null) {
                LOG.warn("Couldn't find an aggregate for column: {}", metadata.getColumnName());
                continue;
              }

              Value convertedValue =
                  QueryAndGatewayDtoConverter.convertToGatewayValueForMetricValue(
                      MetricAggregationFunctionUtil.getValueTypeFromFunction(
                          timeAggregation.getAggregation().getFunction(), attributeMetadataMap),
                      attributeMetadataMap,
                      metadata,
                      row.getColumn(i));

              List<org.hypertrace.gateway.service.v1.common.Expression> healthExpressions =
                  timeAggregation.getAggregation().getFunction().getArgumentsList().stream()
                      .filter(org.hypertrace.gateway.service.v1.common.Expression::hasHealth)
                      .collect(Collectors.toList());
              Preconditions.checkArgument(healthExpressions.size() <= 1);
              Health health = Health.NOT_COMPUTED;

              MetricSeries.Builder seriesBuilder =
                  metricSeriesMap.computeIfAbsent(
                      metadata.getColumnName(), k -> getMetricSeriesBuilder(timeAggregation));
              seriesBuilder.addValue(
                  Interval.newBuilder(intervalBuilder.build())
                      .setValue(convertedValue)
                      .setHealth(health));
            }
          } else {
            LOG.warn(
                "Was expecting STRING values only but received valueType: {}",
                value.getValueType());
          }
        }
      }
    }

    Map<EntityKey, Entity.Builder> resultMap = new LinkedHashMap<>();
    for (Map.Entry<EntityKey, Map<String, MetricSeries.Builder>> entry :
        entityMetricSeriesMap.entrySet()) {
      Entity.Builder entityBuilder =
          Entity.newBuilder()
              .setEntityType(entitiesRequest.getEntityType())
              .putAllMetricSeries(
                  entry.getValue().entrySet().stream()
                      .collect(
                          Collectors.toMap(
                              Map.Entry::getKey, e -> getSortedMetricSeries(e.getValue()))));
      for (int i = 0; i < idColumns.size(); i++) {
        entityBuilder.putAttribute(
            idColumns.get(i),
            org.hypertrace.gateway.service.v1.common.Value.newBuilder()
                .setString(entry.getKey().getAttributes().get(i))
                .setValueType(org.hypertrace.gateway.service.v1.common.ValueType.STRING)
                .build());
      }
      resultMap.put(entry.getKey(), entityBuilder);
    }
    return new EntityFetcherResponse(resultMap);
  }

  private QueryRequest buildTimeSeriesQueryRequest(
      EntitiesRequest entitiesRequest,
      EntitiesRequestContext entitiesRequestContext,
      long periodSecs,
      List<TimeAggregation> timeAggregationBatch,
      List<String> idColumns,
      String timeColumn) {
    long alignedStartTime =
        QueryExpressionUtil.alignToPeriodBoundary(
            entitiesRequest.getStartTimeMillis(), periodSecs, true);
    long alignedEndTime =
        QueryExpressionUtil.alignToPeriodBoundary(
            entitiesRequest.getEndTimeMillis(), periodSecs, false);
    EntitiesRequest timeAlignedEntitiesRequest =
        EntitiesRequest.newBuilder(entitiesRequest)
            .setStartTimeMillis(alignedStartTime)
            .setEndTimeMillis(alignedEndTime)
            .build();
    EntitiesRequestContext timeAlignedEntitiesRequestContext =
        new EntitiesRequestContext(
            entitiesRequestContext.getTenantId(),
            alignedStartTime,
            alignedEndTime,
            entitiesRequestContext.getEntityType(),
            entitiesRequestContext.getHeaders());

    QueryRequest.Builder builder = QueryRequest.newBuilder();
    timeAggregationBatch.forEach(
        e ->
            builder.addSelection(
                QueryAndGatewayDtoConverter.convertToQueryExpression(e.getAggregation())));

    Filter.Builder queryFilter =
        constructQueryServiceFilter(
            timeAlignedEntitiesRequest, timeAlignedEntitiesRequestContext, idColumns);
    builder.setFilter(queryFilter);

    // First group by the id columns.
    builder.addAllGroupBy(
        idColumns.stream()
            .map(QueryRequestUtil::createColumnExpression)
            .map(Expression.Builder::build)
            .collect(Collectors.toList()));

    // Secondary grouping is on time.
    builder.addGroupBy(
        Expression.newBuilder()
            .setFunction(QueryRequestUtil.createTimeColumnGroupByFunction(timeColumn, periodSecs)));

    // Pinot truncates the GroupBy results to 10 when there is no limit explicitly but
    // here we neither want the results to be truncated nor apply the limit coming from client.
    // We would like to get all entities based on filters so we set the limit to a high value.
    builder.setLimit(QueryServiceClient.DEFAULT_QUERY_SERVICE_GROUP_BY_LIMIT);

    return builder.build();
  }

  /**
   * - Adds the time range to the filter - Adds a non null filter on entity id - Converts it to the
   * query service filter
   */
  private Filter.Builder constructQueryServiceFilter(
      EntitiesRequest entitiesRequest,
      EntitiesRequestContext entitiesRequestContext,
      List<String> entityIdAttributes) {
    Filter.Builder filterBuilder =
        QueryAndGatewayDtoConverter.addTimeFilterAndConvertToQueryFilter(
            entitiesRequest.getStartTimeMillis(),
            entitiesRequest.getEndTimeMillis(),
            AttributeMetadataUtil.getTimestampAttributeId(
                attributeMetadataProvider, entitiesRequestContext, entitiesRequest.getEntityType()),
            entitiesRequest.getFilter());
    // adds the Id != "null" filter to remove null entities.
    return filterBuilder.addChildFilter(
        Filter.newBuilder()
            .setOperator(Operator.AND)
            .addAllChildFilter(
                entityIdAttributes.stream()
                    .map(
                        entityIdAttribute ->
                            QueryRequestUtil.createColumnValueFilter(
                                entityIdAttribute, Operator.NEQ, QUERY_SERVICE_NULL))
                    .map(Filter.Builder::build)
                    .collect(Collectors.toList())));
  }

  private MetricSeries.Builder getMetricSeriesBuilder(
      org.hypertrace.gateway.service.v1.common.TimeAggregation timeAggregation) {
    MetricSeries.Builder series = MetricSeries.newBuilder();
    series.setAggregation(timeAggregation.getAggregation().getFunction().getFunction().name());
    series.setPeriod(timeAggregation.getPeriod());
    return series;
  }
}