package io.featurehub.client

import io.featurehub.sse.model.FeatureStateUpdate
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable

import java.util.concurrent.Future
import java.util.function.Supplier

class FeatureHubTestClientFactory implements FeatureHubClientFactory {
  class FakeEdgeService implements EdgeService {
    final InternalFeatureRepository repository
    final FeatureHubConfig config

    FakeEdgeService(@Nullable InternalFeatureRepository repository, @NotNull FeatureHubConfig config) {
      this.repository = repository ?: config.repository as InternalFeatureRepository
      this.config = config
    }

    @Override
    Future<Readiness> contextChange(@Nullable String newHeader, String contextSha) {
      return null
    }

    @Override
    boolean isClientEvaluation() {
      return false
    }

    @Override
    boolean isStopped() {
      return false
    }

    @Override
    void close() {
    }

    @NotNull
    @Override
    FeatureHubConfig getConfig() {
      return config
    }

    @Override
    Future<Readiness> poll() {
      return null
    }

    @Override
    long currentInterval() {
      return 0
    }
  }

  class FakeTestApi implements TestApi {

    @Override
    TestApiResult setFeatureState(String apiKey, @NotNull String featureKey, @NotNull FeatureStateUpdate featureStateUpdate) {
      return null
    }

    @Override
    TestApiResult setFeatureState(@NotNull String featureKey, @NotNull FeatureStateUpdate featureStateUpdate) {
      return null
    }

    @Override
    void close() {

    }
  }

  static FakeEdgeService fake
  static FakeTestApi fakeTestApi

  @Override
  @NotNull
  Supplier<EdgeService> createSSEEdge(FeatureHubConfig config, InternalFeatureRepository repository) {
    fake = new FakeEdgeService(repository, config)
    return { -> fake }
  }

  @Override
  @NotNull
  Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config) {
    return createSSEEdge(config, null)
  }

  @Override
  @NotNull
  Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config, @Nullable InternalFeatureRepository repository, int timeoutInSeconds, boolean amPolling) {
    fake = new FakeEdgeService(repository, config)
    return { -> fake }
  }

  @Override
  @NotNull
  Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config, int timeoutInSeconds, boolean amPolling) {
    fake = new FakeEdgeService(config.getInternalRepository(), config)
    return { -> fake }
  }

  @Override
  @NotNull
  Supplier<TestApi> createTestApi(@NotNull FeatureHubConfig config) {
    fakeTestApi = new FakeTestApi()
    return { -> fakeTestApi }
  }
}
