package io.featurehub.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class PollingDelegateEdgeService implements EdgeService {
  @NotNull
  private final EdgeService edgeService;
  @NotNull
  private final InternalFeatureRepository repo;
  private Timer timer;
  private static final Logger log = LoggerFactory.getLogger(PollingDelegateEdgeService.class);
  private boolean busy = false;

  /**
   * This class has to get the timeout delay from the underlying client because the server can override the timeout delay.
   *
   * @param edgeService - the Rest client that is polling. It MUST NOT BE an SSE client.
   * @param repo - the internal repo API.
   */

  public PollingDelegateEdgeService(@NotNull EdgeService edgeService, @NotNull InternalFeatureRepository repo) {
    this.edgeService = edgeService;
    this.repo = repo;
    timer = newTimer();
  }

  protected Timer newTimer() {
    return new Timer(true);
  }

  private void loop() {
    if (!edgeService.isStopped()) {
      busy = false;

      timer = newTimer(); // once its cancelled, you can't reuse it
      timer.schedule(new TimerTask() {
        @Override
        public void run() {
          poll();
        }
      }, edgeService.currentInterval() * 1000);
    }
  }

  private void cancelTimer() {
    if (timer != null) {
      timer.cancel();
    }
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, String contextSha) {
    if (edgeService.needsContextChange(newHeader, contextSha)) {
      log.trace("contextChange");
      cancelTimer();

      return repo.getExecutor().submit(() -> {
          try {
            log.trace("context change");
            return edgeService.contextChange(newHeader, contextSha).get();
          } catch (Exception e) {
            log.error("failed to context change", e);
            return repo.getReadiness();
          } finally {
            log.trace("looping again cc");
            loop();
          }
        }
      );
    } else {
      return CompletableFuture.completedFuture(repo.getReadiness());
    }
  }

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
    cancelTimer();
    edgeService.close();
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return edgeService.getConfig();
  }

  @Override
  public Future<Readiness> poll() {
    if (!busy) {
      busy = true;
      cancelTimer();

      return repo.getExecutor().submit(() -> {
        log.trace("calling poll directly");
        try {
          return edgeService.poll().get();
        } catch (Exception e) {
          log.error("failed to poll", e);
          return repo.getReadiness();
        } finally {
          log.trace("finished polling");
          loop();
        }
      });
    }

    return CompletableFuture.completedFuture(repo.getReadiness());
  }

  @Override
  public long currentInterval() {
    return edgeService.currentInterval();
  }
}
