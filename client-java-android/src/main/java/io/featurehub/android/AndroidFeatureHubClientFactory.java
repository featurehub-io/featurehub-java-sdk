package io.featurehub.android;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubClientFactory;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public class AndroidFeatureHubClientFactory implements FeatureHubClientFactory {
  @Override
  public Supplier<EdgeService> createEdgeService(@NotNull final FeatureHubConfig config,
                                                 @Nullable final InternalFeatureRepository repository) {
    return () -> new FeatureHubClient(repository, config);
  }

  @Override
  public Supplier<EdgeService> createEdgeService(@NotNull FeatureHubConfig config) {
    return createEdgeService(config, null);
  }
}
