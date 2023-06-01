package io.featurehub.client.jersey;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubClientFactory;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.edge.EdgeRetryer;

import java.util.function.Supplier;

public class JerseyFeatureHubClientFactory implements FeatureHubClientFactory {
  @Override
  public Supplier<EdgeService> createEdgeService(FeatureHubConfig config, InternalFeatureRepository repository) {
    return () -> new JerseySSEClient(repository, config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build());
  }
}
