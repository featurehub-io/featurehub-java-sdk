package io.featurehub.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * A no-operation {@link EdgeService} used when {@link EdgeFeatureHubConfig} is constructed without
 * an edge URL and API key. It never connects to a remote server; callers are expected to load
 * features directly into the repository (e.g. via {@code LocalYamlFeatureStore} or a Redis store).
 */
public class NoopEdgeService implements EdgeService {
  private final FeatureHubConfig config;

  public NoopEdgeService(@NotNull FeatureHubConfig config) {
    this.config = config;
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, String contextSha) {
    return CompletableFuture.completedFuture(config.getReadiness());
  }

  @Override
  public boolean isClientEvaluation() {
    return true;
  }

  @Override
  public boolean isStopped() {
    return false;
  }

  @Override
  public void close() {
    // nothing to close
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  @Override
  public @NotNull Future<Readiness> poll() {
    return CompletableFuture.completedFuture(config.getReadiness());
  }

  @Override
  public long currentInterval() {
    return 0;
  }
}
