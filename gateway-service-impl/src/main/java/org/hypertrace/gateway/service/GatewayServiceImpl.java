package org.hypertrace.gateway.service;

import com.google.common.base.Preconditions;
import com.google.protobuf.ServiceException;
import com.typesafe.config.Config;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.hypertrace.core.attribute.service.client.AttributeServiceClient;
import org.hypertrace.core.attribute.service.client.config.AttributeServiceClientConfig;
import org.hypertrace.core.query.service.client.QueryServiceClient;
import org.hypertrace.core.query.service.client.QueryServiceConfig;
import org.hypertrace.entity.query.service.client.EntityQueryServiceClient;
import org.hypertrace.entity.service.client.config.EntityServiceClientConfig;
import org.hypertrace.gateway.service.common.AttributeMetadataProvider;
import org.hypertrace.gateway.service.common.RequestContext;
import org.hypertrace.gateway.service.common.config.ScopeFilterConfigs;
import org.hypertrace.gateway.service.entity.EntityService;
import org.hypertrace.gateway.service.entity.config.LogConfig;
import org.hypertrace.gateway.service.explore.ExploreService;
import org.hypertrace.gateway.service.span.SpanService;
import org.hypertrace.gateway.service.trace.TracesService;
import org.hypertrace.gateway.service.v1.entity.EntitiesResponse;
import org.hypertrace.gateway.service.v1.entity.UpdateEntityRequest;
import org.hypertrace.gateway.service.v1.entity.UpdateEntityResponse;
import org.hypertrace.gateway.service.v1.explore.ExploreRequest;
import org.hypertrace.gateway.service.v1.explore.ExploreResponse;
import org.hypertrace.gateway.service.v1.span.SpansResponse;
import org.hypertrace.gateway.service.v1.trace.TracesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway service for all entity data. This should be a light weight gateway which looks at the
 * entity type in the incoming requests, translates (if required) the request into the request
 * expected by the downstream service and forwards the response back to the original client (again
 * translating if required). This should not have any business logic, only translation logic.
 */
public class GatewayServiceImpl extends GatewayServiceGrpc.GatewayServiceImplBase {

  private static final Logger LOG = LoggerFactory.getLogger(GatewayServiceImpl.class);

  private final TracesService traceService;
  private final SpanService spanService;
  private final EntityService entityService;
  private final ExploreService exploreService;

  public GatewayServiceImpl(Config appConfig, Config qsConfig) {
    AttributeServiceClientConfig asConfig = AttributeServiceClientConfig.from(appConfig);
    AttributeServiceClient asClient =
        new AttributeServiceClient(asConfig.getHost(), asConfig.getPort());
    AttributeMetadataProvider attributeMetadataProvider = new AttributeMetadataProvider(asClient);

    QueryServiceClient queryServiceClient =
        new QueryServiceClient(new QueryServiceConfig(qsConfig));
    EntityQueryServiceClient eqsClient =
        new EntityQueryServiceClient(EntityServiceClientConfig.from(appConfig));
    ScopeFilterConfigs scopeFilterConfigs = new ScopeFilterConfigs(appConfig);
    LogConfig logConfig = new LogConfig(appConfig);
    this.traceService =
        new TracesService(queryServiceClient, attributeMetadataProvider, scopeFilterConfigs);
    this.spanService = new SpanService(queryServiceClient, attributeMetadataProvider);
    this.entityService =
        new EntityService(queryServiceClient, eqsClient, attributeMetadataProvider, logConfig);
    this.exploreService =
        new ExploreService(queryServiceClient, attributeMetadataProvider, scopeFilterConfigs);
  }

  @Override
  public void getTraces(
      org.hypertrace.gateway.service.v1.trace.TracesRequest request,
      io.grpc.stub.StreamObserver<org.hypertrace.gateway.service.v1.trace.TracesResponse>
          responseObserver) {

    Optional<String> tenantId =
        org.hypertrace.core.grpcutils.context.RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    try {
      RequestContext requestContext =
          new RequestContext(
              tenantId.get(),
              org.hypertrace.core.grpcutils.context.RequestContext.CURRENT.get()
                  .getRequestHeaders());

      TracesResponse response = traceService.getTracesByFilter(requestContext, request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      LOG.error("Error while handling traces request: {}", request, e);
      responseObserver.onError(e);
    }
  }

  @Override
  public void getSpans(
      org.hypertrace.gateway.service.v1.span.SpansRequest request,
      io.grpc.stub.StreamObserver<org.hypertrace.gateway.service.v1.span.SpansResponse>
          responseObserver) {
    Optional<String> tenantId =
        org.hypertrace.core.grpcutils.context.RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    try {
      RequestContext context =
          new RequestContext(
              tenantId.get(),
              org.hypertrace.core.grpcutils.context.RequestContext.CURRENT.get()
                  .getRequestHeaders());
      SpansResponse response = spanService.getSpansByFilter(context, request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      LOG.error("Error while handling spans request: {}", request, e);
      responseObserver.onError(e);
    }
  }

  @Override
  public void getEntities(
      org.hypertrace.gateway.service.v1.entity.EntitiesRequest request,
      StreamObserver<org.hypertrace.gateway.service.v1.entity.EntitiesResponse> responseObserver) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Received request: {}", request);
    }

    Optional<String> tenantId =
        org.hypertrace.core.grpcutils.context.RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    try {
      Preconditions.checkArgument(
          StringUtils.isNotBlank(request.getEntityType()),
          "EntityType is mandatory in the request.");

      Preconditions.checkArgument(
          request.getSelectionCount() > 0, "Selection list can't be empty in the request.");

      Preconditions.checkArgument(
          request.getStartTimeMillis() > 0
              && request.getEndTimeMillis() > 0
              && request.getStartTimeMillis() < request.getEndTimeMillis(),
          "Invalid time range. Both start and end times have to be valid timestamps.");

      EntitiesResponse response =
          entityService.getEntities(
              tenantId.get(),
              request,
              org.hypertrace.core.grpcutils.context.RequestContext.CURRENT.get()
                  .getRequestHeaders());

      if (LOG.isDebugEnabled()) {
        LOG.debug("Received response: {}", response);
      }

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      LOG.error("Error while handling entities request: {}.", request, e);
      responseObserver.onError(e);
    }
  }

  @Override
  public void updateEntity(
      UpdateEntityRequest request, StreamObserver<UpdateEntityResponse> responseObserver) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Received request: {}", request);
    }

    Optional<String> tenantId =
        org.hypertrace.core.grpcutils.context.RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    try {
      UpdateEntityResponse response =
          entityService.updateEntity(
              tenantId.get(),
              request,
              org.hypertrace.core.grpcutils.context.RequestContext.CURRENT.get()
                  .getRequestHeaders());

      if (LOG.isDebugEnabled()) {
        LOG.debug("Received response: {}", response);
      }
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      LOG.error("Error while handling UpdateEntityRequest: {}.", request, e);
      responseObserver.onError(e);
    }
  }

  @Override
  public void explore(ExploreRequest request, StreamObserver<ExploreResponse> responseObserver) {
    Optional<String> tenantId =
        org.hypertrace.core.grpcutils.context.RequestContext.CURRENT.get().getTenantId();
    if (tenantId.isEmpty()) {
      responseObserver.onError(new ServiceException("Tenant id is missing in the request."));
      return;
    }

    try {
      ExploreResponse response =
          exploreService.explore(
              tenantId.get(),
              request,
              org.hypertrace.core.grpcutils.context.RequestContext.CURRENT.get()
                  .getRequestHeaders());
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      LOG.error("Error while handling explore request: {}", request, e);
      responseObserver.onError(e);
    }
  }
}