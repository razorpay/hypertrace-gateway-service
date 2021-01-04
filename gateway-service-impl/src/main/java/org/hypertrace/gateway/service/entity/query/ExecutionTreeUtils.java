package org.hypertrace.gateway.service.entity.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.hypertrace.core.attribute.service.v1.AttributeSource;
import org.hypertrace.gateway.service.common.util.ExpressionReader;
import org.hypertrace.gateway.service.v1.common.Expression;
import org.hypertrace.gateway.service.v1.common.Filter;
import org.hypertrace.gateway.service.v1.common.Operator;
import org.hypertrace.gateway.service.v1.common.OrderByExpression;

public class ExecutionTreeUtils {
  /**
   * Returns a non-empty optional if all the attributes in the selection(attributes and aggreagtions),
   * time aggregations, filter and order by can be read from the same source.
   * @param executionContext
   * @return
   */
  static Optional<String> getSingleSourceForAllAttributes(ExecutionContext executionContext) {
    Optional<String> singleSourceFromKeySets = getSingleSourceFromKeySets(executionContext);
    if (singleSourceFromKeySets.isPresent()) {
      return singleSourceFromKeySets;
    }

    return getSingleSourceFromAttributeSourceValueSets(executionContext);
  }

  /**
   * Returns a non-empty optional if the size union of all keysets is equal to 1. This means that all
   * the attributes can be read from one source.
   * @param executionContext
   * @return
   */
  private static Optional<String> getSingleSourceFromKeySets(ExecutionContext executionContext) {
    Set<String> selectionsSourceSet = executionContext.getSourceToSelectionExpressionMap().keySet();
    Set<String> metricAggregationsSourceSet = executionContext.getSourceToMetricExpressionMap().keySet();
    Set<String> timeAggregationsSourceSet = executionContext.getSourceToTimeAggregationMap().keySet();
    Set<String> filtersSourceSet = executionContext.getSourceToFilterExpressionMap().keySet();
    Set<String> orderBysSourceSet = executionContext.getSourceToOrderByExpressionMap().keySet();

    Set<String> sources = new HashSet<>();
    sources.addAll(selectionsSourceSet);
    sources.addAll(metricAggregationsSourceSet);
    sources.addAll(timeAggregationsSourceSet);
    sources.addAll(filtersSourceSet);
    sources.addAll(orderBysSourceSet);
    if (sources.size() == 1) {
      return sources.stream().findFirst();
    } else {
      return Optional.empty();
    }
  }

  /**
   * Some attributes can be served by more than 1 source. eg. API.apiDiscoveryState. Check if it is
   * possible to serve all the attributes from a single source by computing the intersection of
   * their sources and checking if it's equal to 1.
   * @param executionContext
   * @return
   */
  private static Optional<String> getSingleSourceFromAttributeSourceValueSets(ExecutionContext executionContext) {
    // Compute the intersection of all sources in attributesToSourcesMap and check if it's size is 1
    Set<AttributeSource> attributeSourcesIntersection = executionContext.getAttributeToSourcesMap().values().stream()
        .filter((sourcesSet) -> !sourcesSet.isEmpty())
        .findFirst()
        .orElse(Set.of());

    if (attributeSourcesIntersection.isEmpty()) {
      return Optional.empty();
    }

    attributeSourcesIntersection = new HashSet<>(attributeSourcesIntersection);

    for (Set<AttributeSource> attributeSourcesSet : executionContext.getAttributeToSourcesMap().values()) {
      // retainAll() for sets computes the intersections.
      attributeSourcesIntersection.retainAll(attributeSourcesSet);
    }

    if (attributeSourcesIntersection.size() == 1) {
      return attributeSourcesIntersection.stream().map(Enum::name).findFirst();
    } else {
      return Optional.empty();
    }
  }

  /**
   * Returns true if the filter will AND on the Entity Id EQ filter. Will work as well for multiple
   * expressions, read from executionContext entity id expressions, that compose the Entity Id.
   * @param executionContext
   * @return
   */
  static boolean hasEntityIdEqualsFilter(ExecutionContext executionContext) {
    Filter filter = executionContext.getEntitiesRequest().getFilter();
    if (filter.equals(Filter.getDefaultInstance())) {
      return false;
    }

    List<Expression> entityIdExpressionList = executionContext.getEntityIdExpressions();
    // No known entity Ids
    if (entityIdExpressionList.size() == 0) {
      return false;
    }

    // Simple EQ filter without ANDs that is the equality filter. Only works when there's only one
    // entityId column. If there were multiple, then this would be an AND filter.
    if (entityIdExpressionList.size() == 1 && simpleFilterEntityIdEqualsFilter(filter, entityIdExpressionList)){
      return true;
    }

    if (filter.getOperator() == Operator.AND && filter.getChildFilterCount() == 0) {
      return false;
    }

    // OR or NOT operator in the filter means that highly likely this is not a straight equality
    // filter.
    if (containsNotOrOrFilter(filter)) {
      return false;
    }

    return hasEntityIdEqualsFilter(filter, entityIdExpressionList);
  }

  private static boolean hasEntityIdEqualsFilter(Filter filter,
                                                 List<Expression> entityIdExpressionList) {
    Operator operator = filter.getOperator();

    if (operator != Operator.AND) {
      return false;
    }

    if (filter.getChildFilterCount() == 0) {
      return false;
    }

    List<Filter> childFilters = filter.getChildFilterList();
    int entityIdsMatched = 0;

    for (Filter childFilter : childFilters) {
      Operator childFilterOperator = childFilter.getOperator();
      if (childFilterOperator == Operator.AND) {
        if (hasEntityIdEqualsFilter(childFilter, entityIdExpressionList)) {
          return true;
        }
      } else if (simpleFilterEntityIdEqualsFilter(childFilter, entityIdExpressionList)) {
        entityIdsMatched++;
      }
    }

    return entityIdsMatched == entityIdExpressionList.size();
  }

  private static boolean simpleFilterEntityIdEqualsFilter(Filter filter,
                                                          List<Expression> entityIdExpressionList) {
    if (filter.getOperator() == Operator.EQ && filter.hasLhs() && filter.getLhs().hasColumnIdentifier()) {
      for (Expression entityIdExpression : entityIdExpressionList) {
        if (filter.getLhs().getColumnIdentifier().getColumnName().equals(entityIdExpression.getColumnIdentifier().getColumnName())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean containsNotOrOrFilter(Filter filter) {
    Operator operator = filter.getOperator();
    if (operator == Operator.OR || operator == Operator.NOT) {
      return true;
    }

    if (filter.getChildFilterCount() == 0) {
      return false;
    }

    for (Filter childFilter : filter.getChildFilterList()) {
      if (containsNotOrOrFilter(childFilter)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Computes common set of sources, if both filters and order bys are requested on the same source
   * sets
   *
   * Look at {@link ExecutionTreeUtils#getIntersectingSourceSets(java.util.Map, java.util.Map)}
   */
  public static Set<String> getSourceSetsIfFilterAndOrderByAreFromSameSourceSets(
      ExecutionContext executionContext) {
    Map<String, List<Expression>> sourceToFilterExpressionMap =
        executionContext.getSourceToFilterExpressionMap();
    Map<String, List<OrderByExpression>> sourceToOrderByExpressionMap =
        executionContext.getSourceToOrderByExpressionMap();

    // A weird case, if there are no filters and order bys
    if (sourceToFilterExpressionMap.isEmpty() && sourceToOrderByExpressionMap.isEmpty()) {
      return Collections.emptySet();
    }

    Map<String, Set<String>> filterAttributeToSourcesMap =
        buildAttributeToSourcesMap(sourceToFilterExpressionMap);
    Map<String, Set<String>> orderByAttributeToSourceMap =
        buildAttributeToSourcesMap(
            sourceToOrderByExpressionMap.entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        entry ->
                            entry.getValue().stream()
                                .map(OrderByExpression::getExpression)
                                .collect(Collectors.toList()))));

    return getIntersectingSourceSets(filterAttributeToSourcesMap, orderByAttributeToSourceMap);
  }

  /**
   * Given a source to expression map, builds an attribute to sources map, where attribute value is extracted
   * out from the expression. Basically, a reverse map of the map provided as input
   *
   * Example:
   * ("QS" -> API.id, "QS" -> API.name, "EDS" -> API.id) =>
   *
   * ("API.id" -> ["QS", "EDS"], "API.name" -> "QS")
   */
  private static Map<String, Set<String>> buildAttributeToSourcesMap(
      Map<String, List<Expression>> sourceToExpressionMap) {
    Map<String, Set<String>> attributeToSourceMap = new HashMap<>();

    for (Map.Entry<String, List<Expression>> entry : sourceToExpressionMap.entrySet()) {
      String source = entry.getKey();
      List<Expression> expressions = entry.getValue();
      for (Expression expression : expressions) {
        Set<String> columnNames = ExpressionReader.extractColumns(expression);
        for (String columnName : columnNames) {
          attributeToSourceMap.computeIfAbsent(columnName, k -> new HashSet<>()).add(source);
        }
      }
    }
    return attributeToSourceMap;
  }

  /**
   * Computes intersecting source sets from 2 attribute to sources map
   * i.e. computes intersection source sets across all attributes from the map
   *
   * Examples:
   * 1.
   * ("API.id" -> ["EDS", "QS"], "API.name" -> ["QS", "EDS"])
   * ("API.id" -> ["EDS", "QS"], "API.discoveryState" -> ["EDS"])
   *
   * The intersecting source set across all the attributes would be ["EDS"]
   *
   * 2.
   * ("API.id" -> ["EDS", "QS"], "API.name" -> ["QS", "EDS"])
   * ("API.id" -> ["EDS", "QS"], "API.discoveryState" -> ["EDS", "QS"])
   *
   * The intersecting source set across all the attributes would be ["EDS", "QS"]
   *
   * 3.
   * ("API.id" -> ["EDS"], "API.name" -> ["EDS"])
   * ("API.id" -> ["EDS"], "API.discoveryState" -> ["QS"])
   *
   * The intersecting source set across all the attributes would be []
   */
  private static Set<String> getIntersectingSourceSets(
      Map<String, Set<String>> attributeToSourcesMapFirst,
      Map<String, Set<String>> attributeToSourcesMapSecond) {
    if (attributeToSourcesMapFirst.isEmpty() && attributeToSourcesMapSecond.isEmpty()) {
      return Collections.emptySet();
    }

    if (attributeToSourcesMapFirst.isEmpty()) {
      return getIntersectingSourceSets(attributeToSourcesMapSecond);
    }

    if (attributeToSourcesMapSecond.isEmpty()) {
      return getIntersectingSourceSets(attributeToSourcesMapFirst);
    }

    Set<String> intersectingSourceSetFirst = getIntersectingSourceSets(attributeToSourcesMapFirst);
    Set<String> intersectingSourceSetSecond =
        getIntersectingSourceSets(attributeToSourcesMapSecond);

    intersectingSourceSetFirst.retainAll(intersectingSourceSetSecond);
    return intersectingSourceSetFirst;
  }

  /**
   * Computes source sets intersection from attribute to sources map
   *
   * Examples:
   * ("API.id" -> ["EDS", "QS], "API.name" -> ["QS", "EDS]) => ["QS", "EDS]
   * ("API.id" -> ["EDS", "QS], "API.name" -> ["QS"]) => ["QS"]
   * ("API.id" -> ["EDS"], "API.name" -> ["QS]) => []
   */
  private static Set<String> getIntersectingSourceSets(
      Map<String, Set<String>> attributeToSourcesMap) {
    if (attributeToSourcesMap.isEmpty()) {
      return Collections.emptySet();
    }

    List<Set<String>> listOfSourceSets = new ArrayList<>(attributeToSourcesMap.values());

    return listOfSourceSets.stream()
        .skip(1)
        .collect(() -> new HashSet<>(listOfSourceSets.get(0)), Set::retainAll, Set::retainAll);
  }
}
