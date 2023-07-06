package io.featurehub.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class PollingDelegateEdgeService implements EdgeService {
  @NotNull private final EdgeService edgeService;
  @NotNull private final InternalFeatureRepository repo;
  @NotNull private final Timer timer;

  public PollingDelegateEdgeService(@NotNull EdgeService edgeService, @NotNull InternalFeatureRepository repo) {
    this.edgeService = edgeService;
    this.repo = repo;
    timer = new Timer();
  }

  private void loop() {
    if (!edgeService.isStopped()) {
      timer.schedule(new TimerTask() {
        @Override
        public void run() {
          poll();
        }
      }, edgeService.currentInterval() * 1000);
    }
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, String contextSha) {
    timer.cancel();
    return CompletableFuture.supplyAsync(() -> {
      try {
        Readiness r = edgeService.contextChange(newHeader, contextSha).get();
        loop();
        return r;
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
      }
    }, repo.getExecutor());
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
    timer.cancel();
    edgeService.close();
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return edgeService.getConfig();
  }

  @Override
  public Future<Readiness> poll() {
    timer.cancel();
    return CompletableFuture.supplyAsync(() -> {
      try {
        Readiness r = edgeService.poll().get();
        loop();
        return r;
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
      }

    }, repo.getExecutor());
  }

  @Override
  public long currentInterval() {
    return edgeService.currentInterval();
  }
}
