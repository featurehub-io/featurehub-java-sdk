package io.featurehub.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public interface FeatureHubClientFactory {
  /**
   * allows the creation of a new edge service without knowing about the underlying implementation.
   * depending on which library is included, this will automatically be created.
   *
   * @param config - the full edge config
   * @return
   */
  Supplier<EdgeService> createEdgeService(@NotNull FeatureHubConfig config, @Nullable InternalFeatureRepository repository);

  Supplier<EdgeService> createEdgeService(@NotNull FeatureHubConfig config);
}
