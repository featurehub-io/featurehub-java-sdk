package io.featurehub.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * This class is ONLY used when we are doing client side evaluation. So the edge service stays the same.
 */
class ClientEvalFeatureContext extends BaseClientContext {

  public ClientEvalFeatureContext(FeatureHubConfig config, InternalFeatureRepository repository,
                                  EdgeService edgeService) {
    super(repository, config, edgeService);
  }

  // this doesn't matter for client eval
  @Override
  public Future<ClientContext> build() {
    return CompletableFuture.supplyAsync(() -> {
      try {
        edgeService.contextChange(null, "0").get();
      } catch (InterruptedException|ExecutionException ignored) {
      }

      return this;
    });
  }

  /**
   * We never close anything, it is controlled in the FeatureConfig
   */
  @Override
  public void close() {
  }
}
