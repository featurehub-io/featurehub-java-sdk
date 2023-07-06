package io.featurehub.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * allows the creation of a new edge service without knowing about the underlying implementation.
 * depending on which library is included, this will automatically be created.
 */
public interface FeatureHubClientFactory {

  Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config, @Nullable InternalFeatureRepository repository);

  Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config);

  Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config,
                                       @Nullable InternalFeatureRepository repository,
                                       int timeoutInSeconds, boolean amPollingDelegate);

  Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config, int timeoutInSeconds, boolean amPollingDelegate);

  Supplier<TestApi> createTestApi(@NotNull FeatureHubConfig config);
}
