package io.featurehub.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;
import java.util.function.Supplier;

public class EdgeFeatureHubConfig implements FeatureHubConfig {
  private static final Logger log = LoggerFactory.getLogger(EdgeFeatureHubConfig.class);
  private final String realtimeUrl;
  private final boolean serverEvaluation;
  private final String edgeUrl;
  private final String apiKey;
  private FeatureRepositoryContext repository;
  private Supplier<EdgeService> edgeService;

  public EdgeFeatureHubConfig(@NotNull String edgeUrl, @NotNull String apiKey) {

    if (apiKey == null || edgeUrl == null) {
      throw new RuntimeException("Both edge url and sdk key must be set.");
    }

    serverEvaluation = !FeatureHubConfig.sdkKeyIsClientSideEvaluated(apiKey);

    if (edgeUrl.endsWith("/")) {
      edgeUrl = edgeUrl.substring(0, edgeUrl.length()-1);
    }

    if (edgeUrl.endsWith("/features")) {
      edgeUrl = edgeUrl.substring(0, edgeUrl.length() - "/features".length());
    }

    this.edgeUrl = String.format("%s", edgeUrl);
    this.apiKey = apiKey;

    realtimeUrl = String.format("%s/features/%s", edgeUrl, apiKey);
  }

  @Override
  @NotNull
  public String getRealtimeUrl() {
    return realtimeUrl;
  }

  @Override
  @NotNull
  public String apiKey() {
    return apiKey;
  }

  @Override
  @NotNull
  public String baseUrl() {
    return edgeUrl;
  }

  @Override
  public void init() {
    try {
      newContext().build().get();
    } catch (Exception e) {
      log.error("Failed to initialize FeatureHub client", e);
    }
  }

  @Override
  public boolean isServerEvaluation() {
    return serverEvaluation;
  }

  @Override
  @NotNull
  public ClientContext newContext() {
    return newContext(null, null);
  }

  @Override
  @NotNull
  public ClientContext newContext(@Nullable FeatureRepositoryContext repository,
                                  @Nullable Supplier<EdgeService> edgeService) {
    if (repository == null) {
      if (this.repository == null) {
        this.repository = new ClientFeatureRepository();
      }

      repository = this.repository;
    }

    if (edgeService == null) {
      if (this.edgeService == null) {
        this.edgeService = loadEdgeService(repository);
      }

      edgeService = this.edgeService;
    }

    if (isServerEvaluation()) {
      return new ServerEvalFeatureContext(this, repository, edgeService);
    }

    return new ClientEvalFeatureContext(this, repository, edgeService.get());
  }

  /**
   * dynamically load an edge service implementation
   */
  @NotNull
  protected Supplier<EdgeService> loadEdgeService(@NotNull  FeatureRepositoryContext repository) {
    ServiceLoader<FeatureHubClientFactory> loader = ServiceLoader.load(FeatureHubClientFactory.class);

    for(FeatureHubClientFactory f : loader) {
      Supplier<EdgeService> edgeService = f.createEdgeService(this, repository);
      if (edgeService != null) {
        return edgeService;
      }
    }

    throw new RuntimeException("Unable to find an edge service for featurehub, please include one on classpath.");
  }

  @Override
  public void setRepository(@NotNull FeatureRepositoryContext repository) {
    this.repository = repository;
  }

  @Override
  @NotNull
  public FeatureRepositoryContext getRepository() {
    if (repository == null) {
      repository = new ClientFeatureRepository();
    }

    return repository;
  }

  @Override
  public void setEdgeService(@NotNull Supplier<EdgeService> edgeService) {
    this.edgeService = edgeService;
  }

  @Override
  @NotNull
  public Supplier<EdgeService> getEdgeService() {
    if (edgeService == null) {
      edgeService = loadEdgeService(getRepository());
    }

    return edgeService;
  }

  @Override
  public void addReadynessListener(@NotNull ReadynessListener readynessListener) {
    getRepository().addReadynessListener(readynessListener);
  }

  @Override
  public void addAnalyticCollector(@NotNull AnalyticsCollector collector) {
    getRepository().addAnalyticCollector(collector);
  }

  @Override
  public void registerValueInterceptor(boolean allowLockOverride, @NotNull FeatureValueInterceptor interceptor) {
    getRepository().registerValueInterceptor(allowLockOverride, interceptor);
  }

  @Override
  @NotNull
  public Readyness getReadyness() {
    return getRepository().getReadyness();
  }

  @Override
  public void setJsonConfigObjectMapper(@NotNull ObjectMapper jsonConfigObjectMapper) {
  }
}
