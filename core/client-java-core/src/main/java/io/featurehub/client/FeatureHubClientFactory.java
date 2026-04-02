package io.featurehub.client;

import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * allows the creation of a new edge service without knowing about the underlying implementation.
 * depending on which library is included, this will automatically be created.
 */
public interface FeatureHubClientFactory {

  @NotNull Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config, @Nullable InternalFeatureRepository repository);

  @NotNull Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config);

  // amPollingDelegate is no longer used
  @NotNull Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config,
                                       @Nullable InternalFeatureRepository repository,
                                       int timeoutInSeconds, boolean amPollingDelegate);

  @NotNull Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config, int timeoutInSeconds, boolean amPollingDelegate);

  @NotNull Supplier<TestApi> createTestApi(@NotNull FeatureHubConfig config);
}
