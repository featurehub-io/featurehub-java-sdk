package io.featurehub.client;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PassivePollingDelegateEdgeService extends BasePollingDelegateEdgeService {
  private static final Logger log =
      LoggerFactory.getLogger(PassivePollingDelegateEdgeService.class);
  private LocalDateTime whenLastPolled;

  public PassivePollingDelegateEdgeService(
      @NotNull EdgeService edgeService, @NotNull InternalFeatureRepository repo) {
    super(edgeService, repo);

    // this ensures we trigger as soon as requested
    whenLastPolled = LocalDateTime.now().minusMinutes(1);
  }

  // this ensures we have something to investigate
  @Override
  public void postPollActivity() {
    // do this first to ensure no-one tries to poll again
    whenLastPolled = LocalDateTime.now();
    // now clean up all the clients
    super.postPollActivity();
  }

  @Override
  public Future<Readiness> poll() {
    if (whenLastPolled.plusSeconds(edgeService.currentInterval()).isBefore(LocalDateTime.now())) {
      return super.poll();
    }

    return CompletableFuture.completedFuture(repo.getReadiness());
  }
}
