package io.featurehub.okhttp;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubClientFactory;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.TestApi;
import io.featurehub.client.edge.EdgeRetryer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public class OkHttpFeatureHubFactory implements FeatureHubClientFactory {
  @Override
  public Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config, @Nullable InternalFeatureRepository repository) {
    return () -> new SSEClient(repository, config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build());
  }

  @Override
  public Supplier<EdgeService> createSSEEdge(@NotNull FeatureHubConfig config) {
    return createSSEEdge(config, null);
  }

  @Override
  public Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config, @Nullable InternalFeatureRepository repository, int timeoutInSeconds, boolean amPollingDelegate) {
    return () -> new RestClient(repository, config, timeoutInSeconds, amPollingDelegate);
  }

  @Override
  public Supplier<EdgeService> createRestEdge(@NotNull FeatureHubConfig config, int timeoutInSeconds, boolean amPollingDelegate) {
    return createRestEdge(config, null, timeoutInSeconds, amPollingDelegate);
  }

  @Override
  public Supplier<TestApi> createTestApi(@NotNull FeatureHubConfig config) {
    return () -> new TestClient(config);
  }
}
