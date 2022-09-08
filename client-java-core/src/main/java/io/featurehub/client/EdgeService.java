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
  Future<Readyness> contextChange(@Nullable String newHeader, String contextSha);

  /**
   * are we doing client side evaluation?
   * @return
   */
  boolean isClientEvaluation();

  /**
   * Shut down this service
   */
  void close();

  @NotNull
  FeatureHubConfig getConfig();

  boolean isRequiresReplacementOnHeaderChange();

  void poll();
}
