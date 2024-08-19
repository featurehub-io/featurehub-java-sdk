package io.featurehub.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.featurehub.client.usage.UsageAdapter;
import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsagePlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class EdgeFeatureHubConfig implements FeatureHubConfig {
  private static final Logger log = LoggerFactory.getLogger(EdgeFeatureHubConfig.class);

  @NotNull
  private final String realtimeUrl;
  private final boolean serverEvaluation;
  @NotNull
  private final String edgeUrl;
  @NotNull
  private final List<String> apiKeys;
  @NotNull
  private InternalFeatureRepository repository = new ClientFeatureRepository();
  @Nullable
  private EdgeService edgeService;
  @Nullable
  private Supplier<EdgeService> edgeServiceSupplier;

  @Nullable private ServerEvalFeatureContext serverEvalFeatureContext;

  @Nullable ServiceLoader<FeatureHubClientFactory> loader;

  @Nullable TestApi testApi;

  @NotNull private final UsageAdapter usageAdapter;

  private EdgeType edgeType = EdgeType.REST_PASSIVE;
  private int timeout;

  public EdgeFeatureHubConfig(@NotNull String edgeUrl, @NotNull String apiKey) {
    this(edgeUrl, Collections.singletonList(apiKey));
  }

  public EdgeFeatureHubConfig(@NotNull String edgeUrl, @NotNull List<String> apiKeys) {
    this.apiKeys = apiKeys;

    if (this.apiKeys.isEmpty()) {
      throw new RuntimeException("Cannot use empty list of sdk keys");
    }

    serverEvaluation = !FeatureHubConfig.sdkKeyIsClientSideEvaluated(apiKeys);

    // set defaults
    if (serverEvaluation) {
      restPassive();
    } else {
      streaming();
    }

    if (edgeUrl.endsWith("/")) {
      edgeUrl = edgeUrl.substring(0, edgeUrl.length()-1);
    }

    if (edgeUrl.endsWith("/features")) {
      edgeUrl = edgeUrl.substring(0, edgeUrl.length() - "/features".length());
    }

    this.edgeUrl = String.format("%s", edgeUrl);

    realtimeUrl = String.format("%s/features/%s", edgeUrl, apiKeys.get(0));

    usageAdapter = new UsageAdapter(repository);
  }

  @Override
  public FeatureHubConfig registerUsagePlugin(@NotNull UsagePlugin plugin) {
    usageAdapter.registerPlugin(plugin);
    return this;
  }

  @Override
  @NotNull
  public String getRealtimeUrl() {
    return realtimeUrl;
  }

  @Override
  @NotNull
  public String apiKey() {
    return apiKeys.get(0);
  }

  @Override
  public @NotNull List<String> apiKeys() {
    return apiKeys;
  }

  @Override
  @NotNull
  public String baseUrl() {
    return edgeUrl;
  }

  /**
   * This provides an async wait to trigger off the client.
   */
  @Override
  public Future<ClientContext> init() {
    return newContext().build();
  }

  @Override
  public boolean isServerEvaluation() {
    return serverEvaluation;
  }

  @Override
  @NotNull
  public ClientContext newContext() {
    if (this.edgeService == null) {
      this.edgeService = loadEdgeService(repository).get();
    }

    if (isServerEvaluation()) {
      if (serverEvalFeatureContext == null) {
        serverEvalFeatureContext = new ServerEvalFeatureContext(repository, edgeService);
      }

      return serverEvalFeatureContext;
    }

    return new ClientEvalFeatureContext(this, repository, edgeService);
  }

  /**
   * dynamically load an edge service implementation
   */
  @NotNull
  protected Supplier<EdgeService> loadEdgeService(@NotNull  InternalFeatureRepository repository) {
    if (edgeServiceSupplier == null) {
      ServiceLoader<FeatureHubClientFactory> loader = ServiceLoader.load(FeatureHubClientFactory.class);

      for (FeatureHubClientFactory f : loader) {
        if (edgeType == EdgeType.STREAMING) {
          edgeServiceSupplier = f.createSSEEdge(this, repository);
        } else if (edgeType == EdgeType.REST_PASSIVE) {
          edgeServiceSupplier = f.createRestEdge(this, repository, timeout, false);
        } else {
          edgeServiceSupplier = () -> new PollingDelegateEdgeService(
            f.createRestEdge(this, repository, timeout, true).get(),
            repository);
        }
      }
    }

    if (edgeServiceSupplier != null) {
      return edgeServiceSupplier;
    }

    throw new RuntimeException("Unable to find an edge service for featurehub, please include one on classpath.");
  }

  @Override
  public FeatureHubConfig setRepository(@NotNull FeatureRepository repository) {
    this.repository = (InternalFeatureRepository) repository;
    return this;
  }

  @Override
  @NotNull
  public FeatureRepository getRepository() {
    return repository;
  }

  @Override
  public @NotNull InternalFeatureRepository getInternalRepository() {
    return repository;
  }

  @Override
  public FeatureHubConfig setEdgeService(@NotNull Supplier<EdgeService> edgeService) {
    this.edgeServiceSupplier = edgeService;
    return this;
  }

  @Override
  @NotNull
  public Supplier<EdgeService> getEdgeService() {
    return loadEdgeService(repository);
  }

  @Override
  public @NotNull RepositoryEventHandler addReadinessListener(@NotNull Consumer<Readiness> readinessListener) {
    return repository.addReadinessListener(readinessListener);
  }

  @Override
  public FeatureHubConfig registerValueInterceptor(boolean allowLockOverride, @NotNull FeatureValueInterceptor interceptor) {
    getRepository().registerValueInterceptor(allowLockOverride, interceptor);
    return this;
  }


  @Override
  public FeatureHubConfig recordUsageEvent(UsageEvent event) {
    getInternalRepository().recordUsageEvent(event);
    return this;
  }

  @Override
  @NotNull
  public Readiness getReadiness() {
    return getRepository().getReadiness();
  }

  @Override
  public FeatureHubConfig setJsonConfigObjectMapper(@NotNull ObjectMapper jsonConfigObjectMapper) {
    getRepository().setJsonConfigObjectMapper(jsonConfigObjectMapper);
    return this;
  }

  @Override
  public void close() {
    if (edgeService != null) {
      log.trace("closing edge connection");
      edgeService.close();
      edgeService = null;
    }
    if (testApi != null) {
      log.trace("closing test api");
      testApi.close();
      testApi = null;
    }
  }

  @Override
  public FeatureHubConfig streaming() {
    edgeType = EdgeType.STREAMING;
    timeout = 0;
    return this;
  }

  private enum EdgeType {
    STREAMING, REST_PASSIVE, REST_ACTIVE
  }

  @Override
  public FeatureHubConfig restActive() {
    this.timeout = 180;
    edgeType = EdgeType.REST_ACTIVE;
    return this;
  }

  @Override
  public FeatureHubConfig restActive(int intervalInSeconds) {
    this.timeout = intervalInSeconds;
    edgeType = EdgeType.REST_ACTIVE;
    return this;
  }

  @Override
  public FeatureHubConfig restPassive(int cacheTimeoutInSeconds) {
    this.timeout = cacheTimeoutInSeconds;
    edgeType = EdgeType.REST_PASSIVE;
    return this;
  }

  @Override
  public FeatureHubConfig restPassive() {
    this.timeout = 180;
    edgeType = EdgeType.REST_PASSIVE;
    return this;
  }
}
