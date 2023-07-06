package io.featurehub.client.jersey;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubClientFactory;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.TestApi;
import io.featurehub.client.edge.EdgeRetryer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public class JerseyFeatureHubClientFactory implements FeatureHubClientFactory {
  @Override
  public Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config,
                                             @Nullable InternalFeatureRepository repository) {
    return () -> new JerseySSEClient(repository, config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build());
  }

  @Override
  public Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config) {
    return createSSEEdge(config, null);
  }

  @Override
  public Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config,
                                              @Nullable InternalFeatureRepository repository, int timeoutInSeconds, boolean amPollingDelegate) {
    return () -> new RestClient(repository, null, config, timeoutInSeconds, amPollingDelegate);
  }

  @Override
  public Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config, int timeoutInSeconds, boolean amPollingDelegate) {
    return createRestEdge(config, null, timeoutInSeconds, amPollingDelegate);
  }

  @Override
  public Supplier<TestApi> createTestApi(@NotNull FeatureHubConfig config) {
    return () -> new TestSDKClient(config);
  }
}
