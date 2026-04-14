package io.featurehub.client;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ActivePollingDelegateEdgeService extends BasePollingDelegateEdgeService{
  private Timer timer;
  private static final Logger log = LoggerFactory.getLogger(ActivePollingDelegateEdgeService.class);

  /**
   * This class has to get the timeout delay from the underlying client because the server can override the timeout delay.
   *
   * @param edgeService - the Rest client that is polling. It MUST NOT BE an SSE client.
   * @param repo - the internal repo API.
   */

  public ActivePollingDelegateEdgeService(@NotNull EdgeService edgeService, @NotNull InternalFeatureRepository repo) {
    super(edgeService, repo);
    timer = newTimer();
  }

  protected Timer newTimer() {
    return new Timer(true);
  }

  @Override
  protected void postPollActivity() {
    super.postPollActivity();

    if (!edgeService.isStopped()) {
      timer = newTimer(); // once its canceled, you can't reuse it

      try {
        timer.schedule(new TimerTask() {
          @Override
          public void run() {
            poll();
          }
        }, edgeService.currentInterval() * 1000);
      } catch (IllegalStateException e) {
        // timer was canceled concurrently (e.g. during close() or contextChange()) - not an error
        log.debug("Polling timer cancelled before scheduling, client is likely shutting down");
      }
    }
  }

  // clean up
  @Override
  protected void prePollActivity() {
    if (timer != null) {
      timer.cancel();
    }
  }

  @Override
  public void close() {
    prePollActivity();
    super.close();
  }
}
