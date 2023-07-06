package io.featurehub.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Future;

public interface EdgeService {
  /**
   * called only when the new attribute header has changed
   *
   * @param newHeader - the header to pass to the server if server evaluated
   * @return a completable future when it has actually changed
   */
  @NotNull
  Future<Readiness> contextChange(@Nullable String newHeader, String contextSha);

  /**
   * are we doing client side evaluation?
   * @return
   */
  boolean isClientEvaluation();

  /**
   * Has been stopped for some reason
   * @return true if stopped
   */
  boolean isStopped();

  /**
   * Shut down this service
   */
  void close();

  @NotNull
  FeatureHubConfig getConfig();

  /**
   * @return a future which will be completed when the poll has finished. for SSE this will be the 1st return, for
   * REST it will be the response.
   */
  Future<Readiness> poll();

  /**
   * Only used for REST interfacces, 0 otherwise, and 0 for one-shot calls.
   *
   * @return - current interval which can change based on data sent from server.
   */
  long currentInterval();
}
