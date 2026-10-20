package io.featurehub.client.edge;

/**
 * How every client (SSE or REST) treats the HTTP status Edge answers with.
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
}
