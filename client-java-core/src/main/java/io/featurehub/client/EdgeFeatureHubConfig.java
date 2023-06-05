package io.featurehub.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.featurehub.client.analytics.AnalyticsProvider;
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

  public EdgeFeatureHubConfig(@NotNull String edgeUrl, @NotNull String apiKey) {
    this(edgeUrl, Collections.singletonList(apiKey));
  }

  public EdgeFeatureHubConfig(@NotNull String edgeUrl, @NotNull List<String> apiKeys) {
    this.apiKeys = apiKeys;

    if (this.apiKeys.isEmpty()) {
      throw new RuntimeException("Cannot use empty list of sdk keys");
    }

    serverEvaluation = !FeatureHubConfig.sdkKeyIsClientSideEvaluated(apiKeys);

    if (edgeUrl.endsWith("/")) {
      edgeUrl = edgeUrl.substring(0, edgeUrl.length()-1);
    }

    if (edgeUrl.endsWith("/features")) {
      edgeUrl = edgeUrl.substring(0, edgeUrl.length() - "/features".length());
    }

    this.edgeUrl = String.format("%s", edgeUrl);

    realtimeUrl = String.format("%s/features/%s", edgeUrl, apiKeys.get(0));
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
   * This is only intended to be used for client evaluated contexts, do not use it for server evaluated ones
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

    log.info("xx edge client {}", edgeService);

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
        Supplier<EdgeService> edgeService = f.createEdgeService(this, repository);
        if (edgeService != null) {
          edgeServiceSupplier = edgeService;
          break;
        }
      }
    }

    if (edgeServiceSupplier != null) {
      return edgeServiceSupplier;
    }

    throw new RuntimeException("Unable to find an edge service for featurehub, please include one on classpath.");
  }

  @Override
  public void setRepository(@NotNull FeatureRepository repository) {
    this.repository = (InternalFeatureRepository) repository;
  }

  @Override
  @NotNull
  public FeatureRepository getRepository() {
    return repository;
  }

  @Override
  public void setEdgeService(@NotNull Supplier<EdgeService> edgeService) {
    this.edgeServiceSupplier = edgeService;
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
  public void registerValueInterceptor(boolean allowLockOverride, @NotNull FeatureValueInterceptor interceptor) {
    getRepository().registerValueInterceptor(allowLockOverride, interceptor);
  }

  @Override
  public void registerAnalyticsProvider(@NotNull AnalyticsProvider provider) {
    getRepository().registerAnalyticsProvider(provider);
  }

  @Override
  @NotNull
  public Readiness getReadiness() {
    return getRepository().getReadiness();
  }

  @Override
  public void setJsonConfigObjectMapper(@NotNull ObjectMapper jsonConfigObjectMapper) {
    getRepository().setJsonConfigObjectMapper(jsonConfigObjectMapper);
  }

  @Override
  public void close() {
    log.info("edge client {}", edgeService);
    if (edgeService != null) {
      log.info("closing edge connection");
      edgeService.close();
      edgeService = null;
    }
  }
}
