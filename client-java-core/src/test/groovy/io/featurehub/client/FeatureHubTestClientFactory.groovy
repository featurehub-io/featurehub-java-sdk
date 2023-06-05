package io.featurehub.client

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
  }

  static FakeEdgeService fake

  @Override
  Supplier<EdgeService> createEdgeService(FeatureHubConfig config, InternalFeatureRepository repository) {
    fake = new FakeEdgeService(repository, config)
    return { -> fake }
  }

  @Override
  Supplier<EdgeService> createEdgeService(@NotNull FeatureHubConfig config) {
    return createEdgeService(config, null)
  }
}
