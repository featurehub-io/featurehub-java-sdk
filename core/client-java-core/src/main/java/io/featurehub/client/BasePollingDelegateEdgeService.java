package io.featurehub.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

abstract public class BasePollingDelegateEdgeService implements EdgeService {
  private static final Logger log = LoggerFactory.getLogger(BasePollingDelegateEdgeService.class);
  private boolean busy = false;
  protected List<CompletableFuture<Readiness>> waitingClients = new ArrayList<>();
  @NotNull
  protected final EdgeService edgeService;
  @NotNull protected final InternalFeatureRepository repo;

  public BasePollingDelegateEdgeService(@NotNull EdgeService edgeService, @NotNull InternalFeatureRepository repo) {
    this.edgeService = edgeService;
    this.repo = repo;
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, String contextSha) {
    if (edgeService.isStopped() || !edgeService.needsContextChange(newHeader, contextSha)) {
      return CompletableFuture.completedFuture(repo.getReadiness());
    }

    // busyness does not matter here, we HAVE to poll with the next context change header
    log.trace("[featurehubsdk] poll requires a context header change");

    return repo.getExecutor()
      .submit(
        () -> {
          synchronized (edgeService) {
            busy = true;
            postPollActivity();
          }
          try {
            return edgeService.contextChange(newHeader, contextSha).get();
          } catch (Exception e) {
            log.error("failed to context change", e);
            return repo.getReadiness();
          } finally {
            busy = false;

            log.trace("looping again cc");

            postPollActivity();
          }
        });
  }

  protected void prePollActivity() {
    // do nothing (used by active)
  }

  protected void postPollActivity() {
    List<CompletableFuture<Readiness>> clients = new ArrayList<>();

    // unbusy ourselves and tell all the clients
    synchronized (this) {
      busy = false;
      clients.addAll(waitingClients);
      waitingClients.clear();
    }

    final Readiness readiness = repo.getReadiness();
    clients.forEach(c -> c.complete(readiness));
  }

  @Override
  public Future<Readiness> poll() {
    if (edgeService.isStopped()) {
      return CompletableFuture.completedFuture(repo.getReadiness());
    }

    synchronized (edgeService) {
      if (busy) {
        // add them to the list
        final CompletableFuture<Readiness> change = new CompletableFuture<>();
        waitingClients.add(change);
        return change;
      }

      busy = true;
    }

    return repo.getExecutor()
      .submit(
        () -> {
          log.trace("calling poll directly");
          try {
            return edgeService.poll().get();
          } catch (Exception e) {
            log.error("failed to poll", e);
            return repo.getReadiness();
          } finally {
            log.trace("finished polling");
            postPollActivity();
          }
        });
  }

  /*
   *
   * ALL BELOW ARE PURE DELEGATES
   *
   */
  @Override
  public boolean isClientEvaluation() {
    return edgeService.isClientEvaluation();
  }

  @Override
  public boolean isStopped() {
    return edgeService.isStopped();
  }

  @Override
  public void close() {
    if(!edgeService.isStopped()) {
      edgeService.close();
    }
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return edgeService.getConfig();
  }


  @Override
  public long currentInterval() {
    return edgeService.currentInterval();
  }
}
