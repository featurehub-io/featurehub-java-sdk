package io.featurehub.client

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

class TestContext extends BaseClientContext {
  TestContext(InternalFeatureRepository repo, EdgeService edgeService) {
    super(repo, edgeService)
  }

  @Override
  Future<ClientContext> build() {
    return CompletableFuture.completedFuture(this)
  }

  @Override
  EdgeService getEdgeService() {
    return null
  }

  @Override
  void close() {
  }

  @Override
  boolean exists(String key) {
    return feature(key).exists()
  }
}
