package io.featurehub.client;

import io.featurehub.client.usage.UsageAdapter;
import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsageEventWithFeature;
import io.featurehub.client.usage.UsagePlugin;
import io.featurehub.javascript.JavascriptObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EdgeFeatureHubConfig implements FeatureHubConfig {
  private static final Logger log = LoggerFactory.getLogger(EdgeFeatureHubConfig.class);

  @NotNull
  private final String realtimeUrl;
  private final boolean serverEvaluation;
  @NotNull
  private final String edgeUrl;
  @NotNull
  private final List<String> apiKeys;
  private final UUID environmentId;
  @Nullable
  private InternalFeatureRepository repository = new ClientFeatureRepository();
  @Nullable
  private EdgeService edgeService;
  @Nullable
  private Supplier<EdgeService> edgeServiceSupplier;

  @Nullable private ServerEvalFeatureContext serverEvalFeatureContext;

  @Nullable ServiceLoader<FeatureHubClientFactory> loader;

  @Nullable TestApi testApi;

  @Nullable private UsageAdapter usageAdapter;

  private volatile boolean closed = false;

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

    // when a usage event comes in of the right type, we should tell the passive edge service to poll (check its cache)
    usageAdapter.registerPlugin(new UsagePlugin() {
      @Override
      public void send(UsageEvent event) {
        if (event instanceof UsageEventWithFeature && edgeType == EdgeType.REST_PASSIVE && edgeService != null) {
          edgeService.poll();
        }
      }
    });

    String apiKey = apiKeys.get(0);
    String[] parts = apiKey.split("/");
    // as we only use it in streaming, and streaming only supports 1 API key...
    environmentId = UUID.fromString(parts.length == 3 ? parts[1] : parts[0]);
  }

  public UUID getEnvironmentId() {
    return environmentId;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  private void checkClosed() {
    if (closed) {
      throw new ConfigurationClosedException();
    }
  }

  @Override
  public FeatureHubConfig registerUsagePlugin(@NotNull UsagePlugin plugin) {
    checkClosed();
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
    checkClosed();
    return newContext().build();
  }

  @Override
  public void init(long timeout, TimeUnit unit) {
    checkClosed();
    try {
      final Future<ClientContext> futureContext = newContext().build();
      futureContext.get(timeout, unit);
    } catch (ConfigurationClosedException e) {
      throw e;
    } catch (Exception e) {
      log.warn("Failed to initialize FeatureHub client", e);
    }
  }

  @Override
  public boolean isServerEvaluation() {
    return serverEvaluation;
  }

  @Override
  @NotNull
  public ClientContext newContext() {
    checkClosed();
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
  protected Supplier<EdgeService> loadEdgeService(@NotNull InternalFeatureRepository repository) {
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
    if (closed) return this;
    this.repository = (InternalFeatureRepository) repository;
    return this;
  }

  @Override
  @Nullable
  public FeatureRepository getRepository() {
    return repository;
  }

  @Override
  public @Nullable InternalFeatureRepository getInternalRepository() {
    return repository;
  }

  @Override
  public FeatureHubConfig setEdgeService(@NotNull Supplier<EdgeService> edgeService) {
    if (closed) return this;
    this.edgeServiceSupplier = edgeService;
    return this;
  }

  @Override
  @Nullable
  public Supplier<EdgeService> getEdgeService() {
    if (closed) return null;
    return loadEdgeService(repository);
  }

  @Override
  public @NotNull RepositoryEventHandler addReadinessListener(@NotNull Consumer<Readiness> readinessListener) {
    checkClosed();
    return repository.addReadinessListener(readinessListener);
  }

  @Override
  public FeatureHubConfig registerValueInterceptor(boolean allowLockOverride, @NotNull FeatureValueInterceptor interceptor) {
    checkClosed();
    repository.registerValueInterceptor(allowLockOverride, interceptor);
    return this;
  }

  @Override
  public FeatureHubConfig registerValueInterceptor(@NotNull ExtendedFeatureValueInterceptor interceptor) {
    checkClosed();
    repository.registerValueInterceptor(interceptor);
    return this;
  }

  @Override
  public FeatureHubConfig registerRawUpdateFeatureListener(@NotNull RawUpdateFeatureListener listener) {
    checkClosed();
    repository.registerRawUpdateFeatureListener(listener);
    return this;
  }

  @Override
  public FeatureHubConfig recordUsageEvent(UsageEvent event) {
    if (closed) return this;
    repository.recordUsageEvent(event);
    return this;
  }

  @Override
  @NotNull
  public Readiness getReadiness() {
    if (closed) return Readiness.NotReady;
    return repository.getReadiness();
  }

  @Override
  public FeatureHubConfig setJsonConfigObjectMapper(@NotNull JavascriptObjectMapper jsonConfigObjectMapper) {
    if (closed) return this;
    repository.setJsonConfigObjectMapper(jsonConfigObjectMapper);
    return this;
  }

  @Override
  public boolean waitForReady(long timeout, TimeUnit unit) {
    checkClosed();

    if (edgeService == null) {
      edgeService = loadEdgeService(repository).get();
    }

    edgeService.poll();

    long deadlineMs = System.currentTimeMillis() + unit.toMillis(timeout);

    while (getReadiness() != Readiness.Ready) {
      if (System.currentTimeMillis() >= deadlineMs) {
        return false;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    return true;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;

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
    if (usageAdapter != null) {
      usageAdapter.close();
      usageAdapter = null;
    }
    edgeServiceSupplier = null;
    serverEvalFeatureContext = null;
    repository = null;
  }

  @Override
  public FeatureHubConfig streaming() {
    if (closed) return this;
    edgeType = EdgeType.STREAMING;
    timeout = 0;
    return this;
  }

  private enum EdgeType {
    STREAMING, REST_PASSIVE, REST_ACTIVE
  }

  @Override
  public FeatureHubConfig restActive() {
    if (closed) return this;
    this.timeout = 180;
    edgeType = EdgeType.REST_ACTIVE;
    return this;
  }

  @Override
  public FeatureHubConfig restActive(int intervalInSeconds) {
    if (closed) return this;
    this.timeout = intervalInSeconds;
    edgeType = EdgeType.REST_ACTIVE;
    return this;
  }

  @Override
  public FeatureHubConfig restPassive(int cacheTimeoutInSeconds) {
    if (closed) return this;
    this.timeout = cacheTimeoutInSeconds;
    edgeType = EdgeType.REST_PASSIVE;
    return this;
  }

  @Override
  public FeatureHubConfig restPassive() {
    if (closed) return this;
    this.timeout = 180;
    edgeType = EdgeType.REST_PASSIVE;
    return this;
  }
}
