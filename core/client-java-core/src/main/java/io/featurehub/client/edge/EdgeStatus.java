package io.featurehub.client.edge;

import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.sse.model.SSEResultState;
import org.jetbrains.annotations.NotNull;

/**
 * How every client (SSE or REST) treats the HTTP status Edge answers with, and being told to stop.
 */
public final class EdgeStatus {
  private EdgeStatus() {
  }

  /**
   * @return true if the status means retrying will never change it, so we must permanently stop: the api key doesn't
   * exist (400, 404) or we aren't allowed in (401, 403, which is often a wrong url). Anything else (429, a 5xx and so
   * on) may well go away.
   */
  public static boolean requestsArePointless(int status) {
    return isApiKeyNotFound(status) || isNotPermitted(status);
  }

  /**
   * @return true if the status means the api key doesn't exist
   */
  public static boolean isApiKeyNotFound(int status) {
    return status == 400 || status == 404;
  }

  /**
   * @return true if the status means we aren't allowed in
   */
  public static boolean isNotPermitted(int status) {
    return status == 401 || status == 403;
  }

  /**
   * We will never get features (e.g. the api key doesn't exist). Features we already have are still served, so only
   * if we never had any have we failed.
   *
   * @param source - where the failure came from, e.g. "streaming" or "polling"
   */
  public static void failIfNeverReady(@NotNull InternalFeatureRepository repository, @NotNull String source) {
    if (repository.getReadiness() == Readiness.NotReady) {
      repository.notify(SSEResultState.FAILURE, source);
    }
  }
}
