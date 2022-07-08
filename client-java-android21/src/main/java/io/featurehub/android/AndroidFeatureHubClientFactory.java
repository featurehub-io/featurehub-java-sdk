package io.featurehub.android;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubClientFactory;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.FeatureStore;
import io.featurehub.client.ObjectSupplier;

import java.util.Arrays;

public class AndroidFeatureHubClientFactory implements FeatureHubClientFactory {
  @Override
  public ObjectSupplier<EdgeService> createEdgeService(final FeatureHubConfig config, final FeatureStore repository) {
    return new ObjectSupplier<EdgeService>() {
      @Override
      public EdgeService get() {
        return new FeatureHubClient(config.baseUrl(), Arrays.asList(config.apiKey()), repository, config);
      }
    };
  }
}
