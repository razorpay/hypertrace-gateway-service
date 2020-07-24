package org.hypertrace.gateway.service.common.datafetcher;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.lang3.StringUtils;
import org.hypertrace.core.attribute.service.v1.AttributeKind;
import org.hypertrace.core.attribute.service.v1.AttributeMetadata;
import org.hypertrace.core.attribute.service.v1.AttributeScope;
import org.hypertrace.core.query.service.api.ColumnMetadata;
import org.hypertrace.core.query.service.api.Filter;
import org.hypertrace.core.query.service.api.Operator;
import org.hypertrace.core.query.service.api.QueryRequest;
import org.hypertrace.core.query.service.api.ResultSetChunk;
import org.hypertrace.core.query.service.api.Row;
import org.hypertrace.core.query.service.client.QueryServiceClient;
import org.hypertrace.core.query.service.util.QueryRequestUtil;
import org.hypertrace.gateway.service.common.AttributeMetadataProvider;
import org.hypertrace.gateway.service.common.RequestContext;
import org.hypertrace.gateway.service.common.converters.QueryAndGatewayDtoConverter;
import org.hypertrace.gateway.service.common.util.MetricAggregationFunctionUtil;
import org.hypertrace.gateway.service.entity.EntityKey;
import org.hypertrace.gateway.service.entity.config.InteractionConfig;
import org.hypertrace.gateway.service.entity.config.InteractionConfigs;
import org.hypertrace.gateway.service.v1.common.AggregatedMetricValue;
import org.hypertrace.gateway.service.v1.common.DomainEntityType;
import org.hypertrace.gateway.service.v1.common.Expression;
import org.hypertrace.gateway.service.v1.common.Expression.ValueCase;
import org.hypertrace.gateway.service.v1.common.FunctionExpression;
import org.hypertrace.gateway.service.v1.common.Value;
import org.hypertrace.gateway.service.v1.common.ValueType;
import org.hypertrace.gateway.service.v1.entity.EntitiesRequest;
import org.hypertrace.gateway.service.v1.entity.Entity.Builder;
import org.hypertrace.gateway.service.v1.entity.EntityInteraction;
import org.hypertrace.gateway.service.v1.entity.InteractionsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Business logic to get entity interactions data and aggregate it as per the requests coming into
 * the EntityGateway. As much as possible, this class should be agnostic to the entity type so that
 * all interactions can be modeled similarly in a generic fashion.
 */
public class EntityInteractionsFetcher {

  private static final Logger LOG = LoggerFactory.getLogger(EntityInteractionsFetcher.class);

  // Extracting the incoming/outgoing flag as constant for readability.
  private static final boolean INCOMING = true;
  private static final boolean OUTGOING = false;

  private static final String TIMESTAMP_COLUMN_KEY = "startTime";
  private static final String FROM_ENTITY_TYPE_ATTRIBUTE_ID = "INTERACTION.fromEntityType";
  private static final String TO_ENTITY_TYPE_ATTRIBUTE_ID = "INTERACTION.toEntityType";
  private static final String FROM_ENTITY_ID_ATTRIBUTE_ID = "INTERACTION.fromEntityId";
  private static final String TO_ENTITY_ID_ATTRIBUTE_ID = "INTERACTION.toEntityId";
  private static final Set<String> SELECTIONS_TO_IGNORE =
      ImmutableSet.of(
          FROM_ENTITY_ID_ATTRIBUTE_ID,
          FROM_ENTITY_TYPE_ATTRIBUTE_ID,
          TO_ENTITY_ID_ATTRIBUTE_ID,
          TO_ENTITY_TYPE_ATTRIBUTE_ID);

  private static final String COUNT_COLUMN_NAME = "Count";

  private final QueryServiceClient queryServiceClient;
  private final AttributeMetadataProvider metadataProvider;

  public EntityInteractionsFetcher(
      QueryServiceClient queryServiceClient, AttributeMetadataProvider metadataProvider) {
    this.queryServiceClient = queryServiceClient;
    this.metadataProvider = metadataProvider;
  }

  private List<String> getEntityIdColumnsFromInteraction(
      DomainEntityType entityType, boolean incoming) {
    InteractionConfig interactionConfig =
        InteractionConfigs.getInteractionAttributeConfig(entityType.name());
    if (interactionConfig == null) {
      throw new IllegalArgumentException("Unhandled entityType: " + entityType);
    }
    List<String> columnNames =
        (incoming)
            ? interactionConfig.getCallerSideAttributeIds()
            : interactionConfig.getCalleeSideAttributeIds();
    if (columnNames.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid arguments for getting interaction columns. entityType:%s, incoming:%s",
              entityType, incoming));
    }
    return columnNames;
  }

  public void populateEntityInteractions(
      RequestContext context, EntitiesRequest request, Map<EntityKey, Builder> entityBuilders) {
    // Process the incoming interactions
    if (!InteractionsRequest.getDefaultInstance().equals(request.getIncomingInteractions())) {
      addInteractions(
          context,
          request,
          entityBuilders,
          request.getIncomingInteractions(),
          INCOMING,
          "fromEntityType filter is mandatory for incoming interactions.");
    }

    // Process the outgoing interactions
    if (!InteractionsRequest.getDefaultInstance().equals(request.getOutgoingInteractions())) {
      addInteractions(
          context,
          request,
          entityBuilders,
          request.getOutgoingInteractions(),
          OUTGOING,
          "toEntityType filter is mandatory for outgoing interactions.");
    }
  }

  private void addInteractions(
      RequestContext context,
      EntitiesRequest request,
      Map<EntityKey, Builder> entityIdToBuilders,
      InteractionsRequest interactionsRequest,
      boolean incoming,
      String errorMsg) {

    if (!interactionsRequest.hasFilter()) {
      throw new IllegalArgumentException(errorMsg);
    } else if (interactionsRequest.getSelectionCount() == 0) {
      throw new IllegalArgumentException("Interactions request should have non-empty selections.");
    }

    Map<String, QueryRequest> requests =
        buildQueryRequests(
            request.getStartTimeMillis(),
            request.getEndTimeMillis(),
            request.getEntityType(),
            interactionsRequest,
            entityIdToBuilders.keySet(),
            incoming,
            context);
    if (requests.isEmpty()) {
      throw new IllegalArgumentException(errorMsg);
    }

    Set<String> selectedColumns = new HashSet<>();
    for (Expression expression : interactionsRequest.getSelectionList()) {
      if (expression.getValueCase() == ValueCase.COLUMNIDENTIFIER) {
        selectedColumns.add(expression.getColumnIdentifier().getColumnName());
      }
    }

    Map<String, FunctionExpression> metricToAggFunction =
        MetricAggregationFunctionUtil.getAggMetricToFunction(
            interactionsRequest.getSelectionList());
    for (Map.Entry<String, QueryRequest> entry : requests.entrySet()) {
      Iterator<ResultSetChunk> resultSet =
          queryServiceClient.executeQuery(entry.getValue(), context.getHeaders(), 5000);
      parseResultSet(
          request.getEntityType(),
          entry.getKey(),
          selectedColumns,
          metricToAggFunction,
          resultSet,
          incoming,
          entityIdToBuilders,
          context);
    }
  }

  private Set<String> getOtherEntityTypes(org.hypertrace.gateway.service.v1.common.Filter filter) {
    if (filter.getChildFilterCount() > 0) {
      for (org.hypertrace.gateway.service.v1.common.Filter child : filter.getChildFilterList()) {
        Set<String> result = getOtherEntityTypes(child);
        if (!result.isEmpty()) {
          return result;
        }
      }
    } else {
      if (filter.getLhs().getValueCase() == ValueCase.COLUMNIDENTIFIER) {
        String columnName = filter.getLhs().getColumnIdentifier().getColumnName();

        if (StringUtils.equals(columnName, FROM_ENTITY_TYPE_ATTRIBUTE_ID)
            || StringUtils.equals(columnName, TO_ENTITY_TYPE_ATTRIBUTE_ID)) {
          return getValues(filter.getRhs());
        }
      }
    }

    return Collections.emptySet();
  }

  private Filter.Builder convertToQueryFilter(
      org.hypertrace.gateway.service.v1.common.Filter filter, DomainEntityType otherEntityType) {
    Filter.Builder builder = Filter.newBuilder();
    builder.setOperator(QueryAndGatewayDtoConverter.convertOperator(filter.getOperator()));
    if (filter.getChildFilterCount() > 0) {
      for (org.hypertrace.gateway.service.v1.common.Filter child : filter.getChildFilterList()) {
        builder.addChildFilter(convertToQueryFilter(child, otherEntityType));
      }
    } else {
      if (filter.getLhs().getValueCase() == ValueCase.COLUMNIDENTIFIER) {
        String columnName = filter.getLhs().getColumnIdentifier().getColumnName();

        Filter.Builder entityFilter = null;
        switch (columnName) {
          case FROM_ENTITY_TYPE_ATTRIBUTE_ID:
            entityFilter =
                QueryRequestUtil.createBooleanFilter(
                    Operator.AND,
                    getEntityIdColumnsFromInteraction(otherEntityType, INCOMING).stream()
                        .map(
                            fromEntityIdColumn ->
                                QueryRequestUtil.createColumnValueFilter(
                                    fromEntityIdColumn, Operator.NEQ, "null"))
                        .map(Filter.Builder::build)
                        .collect(Collectors.toList()));
            break;
          case TO_ENTITY_TYPE_ATTRIBUTE_ID:
            entityFilter =
                QueryRequestUtil.createBooleanFilter(
                    Operator.AND,
                    getEntityIdColumnsFromInteraction(otherEntityType, OUTGOING).stream()
                        .map(
                            fromEntityIdColumn ->
                                QueryRequestUtil.createColumnValueFilter(
                                    fromEntityIdColumn, Operator.NEQ, "null"))
                        .map(Filter.Builder::build)
                        .collect(Collectors.toList()));
            break;
          case FROM_ENTITY_ID_ATTRIBUTE_ID:
            entityFilter =
                createFilterForEntityKeys(
                    getEntityIdColumnsFromInteraction(otherEntityType, INCOMING),
                    getEntityKeyValues(filter.getRhs()));
            break;
          case TO_ENTITY_ID_ATTRIBUTE_ID:
            entityFilter =
                createFilterForEntityKeys(
                    getEntityIdColumnsFromInteraction(otherEntityType, OUTGOING),
                    getEntityKeyValues(filter.getRhs()));
            break;
          default:
            // Do nothing.
        }

        return entityFilter;
      }

      // Default case.
      builder.setLhs(QueryAndGatewayDtoConverter.convertToQueryExpression(filter.getLhs()));
      builder.setRhs(QueryAndGatewayDtoConverter.convertToQueryExpression(filter.getRhs()));
    }

    return builder;
  }

  private Set<EntityKey> getEntityKeyValues(Expression expression) {
    Preconditions.checkArgument(expression.getValueCase() == ValueCase.LITERAL);

    Value value = expression.getLiteral().getValue();
    if (value.getValueType() == ValueType.STRING) {
      return Collections.singleton(EntityKey.from(value.getString()));
    } else if (value.getValueType() == ValueType.STRING_ARRAY) {
      return value.getStringArrayList().stream().map(EntityKey::from).collect(Collectors.toSet());
    }
    throw new IllegalArgumentException(
        "Expected STRING value but received unhandled type: " + value.getValueType());
  }

  private Set<String> getValues(Expression expression) {
    Preconditions.checkArgument(expression.getValueCase() == ValueCase.LITERAL);

    Value value = expression.getLiteral().getValue();
    if (value.getValueType() == ValueType.STRING) {
      return Collections.singleton(value.getString());
    } else if (value.getValueType() == ValueType.STRING_ARRAY) {
      return new HashSet<>(value.getStringArrayList());
    }
    throw new IllegalArgumentException(
        "Expected STRING value but received unhandled type: " + value.getValueType());
  }

  @VisibleForTesting
  public Filter.Builder createFilterForEntityKeys(
      List<String> idColumns, Collection<EntityKey> entityKeys) {
    return Filter.newBuilder()
        .setOperator(Operator.OR)
        .addAllChildFilter(
            entityKeys.stream()
                .map(
                    entityKey ->
                        QueryRequestUtil.createValueEQFilter(idColumns, entityKey.getAttributes()))
                .collect(Collectors.toList()));
  }

  @VisibleForTesting
  public Map<String, QueryRequest> buildQueryRequests(
      long startTime,
      long endTime,
      String entityType,
      InteractionsRequest interactionsRequest,
      Set<EntityKey> entityIds,
      boolean incoming,
      RequestContext requestContext) {

    Set<String> entityTypes = getOtherEntityTypes(interactionsRequest.getFilter());
    if (entityTypes.isEmpty()) {
      return Collections.emptyMap();
    }

    QueryRequest.Builder builder = QueryRequest.newBuilder();

    // Filter should include the timestamp filters from parent request first
    Filter.Builder filterBuilder =
        Filter.newBuilder()
            .setOperator(Operator.AND)
            .addChildFilter(
                QueryRequestUtil.createBetweenTimesFilter(
                    metadataProvider
                        .getAttributeMetadata(
                            requestContext, AttributeScope.INTERACTION, TIMESTAMP_COLUMN_KEY)
                        .get()
                        .getId(),
                    startTime,
                    endTime));

    List<String> idColumns =
        getEntityIdColumnsFromInteraction(DomainEntityType.valueOf(entityType), !incoming);

    // Actual entity that we are looking for shouldn't be null.
    filterBuilder.addChildFilter(createFilterForEntityKeys(idColumns, entityIds));

    // Group by the entity id column first, then the other end entity type for the interaction.
    List<org.hypertrace.core.query.service.api.Expression> idExpressions =
        idColumns.stream()
            .map(QueryRequestUtil::createColumnExpression)
            .map(org.hypertrace.core.query.service.api.Expression.Builder::build)
            .collect(Collectors.toList());
    builder.addAllGroupBy(idExpressions);

    List<org.hypertrace.core.query.service.api.Expression.Builder> selections = new ArrayList<>();
    for (Expression expression : interactionsRequest.getSelectionList()) {
      // Ignore the predefined selections because they're handled specially.
      if (expression.getValueCase() == ValueCase.COLUMNIDENTIFIER
          && SELECTIONS_TO_IGNORE.contains(expression.getColumnIdentifier().getColumnName())) {
        continue;
      }

      // Selection should have metrics and attributes that were requested
      selections.add(QueryAndGatewayDtoConverter.convertToQueryExpression(expression));
    }

    // Pinot's GroupBy queries need at least one aggregate operation in the selection
    // so we add count(*) as a dummy placeholder if there are no explicit selectors.
    if (selections.isEmpty()) {
      selections.add(
          QueryRequestUtil.createCountByColumnSelection(idColumns.toArray(new String[] {})));
    }

    QueryRequest protoType = builder.build();
    Filter protoTypeFilter = filterBuilder.build();

    Map<String, QueryRequest> queryRequests = new HashMap<>();

    // In future we could send these queries in parallel to QueryService so that we can reduce the
    // response time.
    for (String e : entityTypes) {
      DomainEntityType otherEntityType = DomainEntityType.valueOf(e.toUpperCase());

      // Get the filters from the interactions request to 'AND' them with the timestamp filter.
      Filter.Builder filterCopy = Filter.newBuilder(protoTypeFilter);
      filterCopy.addChildFilter(
          convertToQueryFilter(interactionsRequest.getFilter(), otherEntityType));

      QueryRequest.Builder builderCopy = QueryRequest.newBuilder(protoType);
      builderCopy.setFilter(filterCopy);

      List<String> otherEntityIdColumns =
          getEntityIdColumnsFromInteraction(otherEntityType, incoming);
      List<org.hypertrace.core.query.service.api.Expression> otherIdExpressions =
          otherEntityIdColumns.stream()
              .map(QueryRequestUtil::createColumnExpression)
              .map(org.hypertrace.core.query.service.api.Expression.Builder::build)
              .collect(Collectors.toList());
      builderCopy.addAllGroupBy(otherIdExpressions);

      // Add all selections in the correct order. First id, then other entity id and finally
      // the remaining selections.
      builderCopy.addAllSelection(idExpressions);
      builderCopy.addAllSelection(otherIdExpressions);

      selections.forEach(builderCopy::addSelection);
      int limit = interactionsRequest.getLimit();
      if (limit > 0) {
        builderCopy.setLimit(limit);
      } else {
        builderCopy.setLimit(QueryServiceClient.DEFAULT_QUERY_SERVICE_GROUP_BY_LIMIT);
      }

      queryRequests.put(e, builderCopy.build());
    }

    return queryRequests;
  }

  private void parseResultSet(
      String entityType,
      String otherEntityType,
      Set<String> selectedColumns,
      Map<String, FunctionExpression> metricToAggFunction,
      Iterator<ResultSetChunk> resultset,
      boolean incoming,
      Map<EntityKey, Builder> entityIdToBuilders,
      RequestContext requestContext) {

    Map<String, AttributeMetadata> attributeMetadataMap =
        metadataProvider.getAttributesMetadata(requestContext, AttributeScope.INTERACTION);

    Map<String, AttributeKind> aliasToAttributeKind =
        MetricAggregationFunctionUtil.getValueTypeFromFunction(
            metricToAggFunction, attributeMetadataMap);

    while (resultset.hasNext()) {
      ResultSetChunk chunk = resultset.next();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Received chunk: " + chunk.toString());
      }

      if (chunk.getRowCount() < 1) {
        break;
      }

      for (Row row : chunk.getRowList()) {
        // Construct the from/to EntityKeys from the columns
        List<String> idColumns =
            getEntityIdColumnsFromInteraction(
                DomainEntityType.valueOf(entityType.toUpperCase()),
                !incoming); // Note: We add the selections it in this order
        EntityKey entityId =
            EntityKey.of(
                IntStream.range(0, idColumns.size())
                    .mapToObj(value -> row.getColumn(value).getString())
                    .toArray(String[]::new));

        List<String> otherIdColumns =
            getEntityIdColumnsFromInteraction(
                DomainEntityType.valueOf(otherEntityType.toUpperCase()), incoming);
        EntityKey otherEntityId =
            EntityKey.of(
                IntStream.range(idColumns.size(), idColumns.size() + otherIdColumns.size())
                    .mapToObj(value -> row.getColumn(value).getString())
                    .toArray(String[]::new));

        EntityInteraction.Builder interaction = EntityInteraction.newBuilder();

        addInteractionEdges(
            interaction,
            selectedColumns,
            incoming ? otherEntityType : entityType,
            incoming ? otherEntityId : entityId,
            incoming ? entityType : otherEntityType,
            incoming ? entityId : otherEntityId);

        for (int i = idColumns.size() + otherIdColumns.size();
            i < chunk.getResultSetMetadata().getColumnMetadataCount();
            i++) {
          ColumnMetadata metadata = chunk.getResultSetMetadata().getColumnMetadata(i);

          // Ignore the count column since we introduced that ourselves into the query.
          if (StringUtils.equals(COUNT_COLUMN_NAME, metadata.getColumnName())) {
            continue;
          }

          // Check if this is an attribute vs metric and set it accordingly on the interaction.
          if (metricToAggFunction.containsKey(metadata.getColumnName())) {
            Value value =
                QueryAndGatewayDtoConverter.convertToGatewayValueForMetricValue(
                    aliasToAttributeKind, attributeMetadataMap, metadata, row.getColumn(i));
            interaction.putMetrics(
                metadata.getColumnName(),
                AggregatedMetricValue.newBuilder()
                    .setValue(value)
                    .setFunction(metricToAggFunction.get(metadata.getColumnName()).getFunction())
                    .build());
          } else {
            interaction.putAttribute(
                metadata.getColumnName(),
                QueryAndGatewayDtoConverter.convertQueryValueToGatewayValue(
                    row.getColumn(i), attributeMetadataMap.get(metadata.getColumnName())));
          }
        }

        if (incoming) {
          entityIdToBuilders.get(entityId).addIncomingInteraction(interaction);
        } else {
          entityIdToBuilders.get(entityId).addOutgoingInteraction(interaction);
        }

        if (LOG.isDebugEnabled()) {
          LOG.debug(interaction.build().toString());
        }
      }
    }
  }

  private void addInteractionEdges(
      EntityInteraction.Builder interaction,
      Set<String> selectedColumns,
      String fromEntityType,
      EntityKey fromEntityId,
      String toEntityType,
      EntityKey toEntityId) {

    if (selectedColumns.contains(FROM_ENTITY_ID_ATTRIBUTE_ID)) {
      interaction.putAttribute(
          FROM_ENTITY_ID_ATTRIBUTE_ID,
          Value.newBuilder()
              .setString(fromEntityId.toString())
              .setValueType(ValueType.STRING)
              .build());
    }
    if (selectedColumns.contains(FROM_ENTITY_TYPE_ATTRIBUTE_ID)) {
      interaction.putAttribute(
          FROM_ENTITY_TYPE_ATTRIBUTE_ID,
          Value.newBuilder().setString(fromEntityType).setValueType(ValueType.STRING).build());
    }
    if (selectedColumns.contains(TO_ENTITY_ID_ATTRIBUTE_ID)) {
      interaction.putAttribute(
          TO_ENTITY_ID_ATTRIBUTE_ID,
          Value.newBuilder()
              .setString(toEntityId.toString())
              .setValueType(ValueType.STRING)
              .build());
    }

    if (selectedColumns.contains(TO_ENTITY_TYPE_ATTRIBUTE_ID)) {
      interaction.putAttribute(
          TO_ENTITY_TYPE_ATTRIBUTE_ID,
          Value.newBuilder().setString(toEntityType).setValueType(ValueType.STRING).build());
    }
  }
}