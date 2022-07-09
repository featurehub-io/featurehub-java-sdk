package io.featurehub.client

import java.util.function.Supplier

class FeatureHubTestClientFactory implements FeatureHubClientFactory {
  static Supplier<EdgeService> edgeServiceSupplier
  static FeatureHubConfig config
  static FeatureStore repository

  @Override
  Supplier<EdgeService> createEdgeService(FeatureHubConfig url, FeatureStore repository) {
    // save them for the test to ch eck
    FeatureHubTestClientFactory.config = url
    FeatureHubTestClientFactory.repository = repository

    return edgeServiceSupplier
  }
}
