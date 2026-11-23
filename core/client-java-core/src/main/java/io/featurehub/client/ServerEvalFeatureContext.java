package io.featurehub.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerEvalFeatureContext extends BaseClientContext {
  private static final Logger log = LoggerFactory.getLogger(ServerEvalFeatureContext.class);
  private String xHeader;
  private final RepositoryEventHandler newFeatureStateHandler;
  private final RepositoryEventHandler featureUpdatedHandler;


  public ServerEvalFeatureContext(InternalFeatureRepository repository,
                                  EdgeService edgeService) {
    super(repository, edgeService);

    newFeatureStateHandler = repository.registerNewFeatureStateAvailable((fr) -> {
      recordRelativeValuesForUser();
    });

    featureUpdatedHandler = repository.registerFeatureUpdateAvailable((fs) -> {
      recordFeatureChangedForUser((FeatureStateBase<?>)fs);
    });
  }

  @Override
  public void close() {
    super.close();

    newFeatureStateHandler.cancel();
    featureUpdatedHandler.cancel();
  }

  @Override
  public Future<ClientContext> build() {
    String newHeader = FeatureStateUtils.generateXFeatureHubHeaderFromMap(attributes);

    if (newHeader != null || xHeader != null) {
      if ((newHeader != null && xHeader == null) || newHeader == null || !newHeader.equals(xHeader)) {
        xHeader = newHeader;

        repository.repositoryNotReady();
      }
    }

    Future<?> change = edgeService.contextChange(newHeader, FeatureStateUtils.contextSha(newHeader));

    xHeader = newHeader;

    CompletableFuture<ClientContext> future = new CompletableFuture<>();

    repository.execute(() -> {
      try {
        change.get();

        future.complete(this);
      } catch (Exception e) {
        log.error("Failed to update", e);
        future.completeExceptionally(e);
      }
    });


    return future;
  }
}
