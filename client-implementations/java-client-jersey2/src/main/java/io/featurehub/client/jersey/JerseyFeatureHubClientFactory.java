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
  @NotNull
  public Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config,
                                             @Nullable InternalFeatureRepository repository) {
    return () -> new JerseySSEClient(repository, config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().build());
  }

  @Override
  @NotNull
  public Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config) {
    return createSSEEdge(config, null);
  }

  @Override
  @NotNull
  public Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config,
                                              @Nullable InternalFeatureRepository repository, int timeoutInSeconds, boolean amPollingDelegate) {
    return () -> new RestClient(repository, null, config,
      EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().rest().build(), timeoutInSeconds, amPollingDelegate);
  }

  @Override
  @NotNull
  public Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config, int timeoutInSeconds, boolean amPollingDelegate) {
    return createRestEdge(config, null, timeoutInSeconds, amPollingDelegate);
  }

  @Override
  @NotNull
  public Supplier<TestApi> createTestApi(@NotNull FeatureHubConfig config) {
    return () -> new TestSDKClient(config);
  }
}
