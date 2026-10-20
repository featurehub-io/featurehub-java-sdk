package io.featurehub.client.edge;

/**
 * How every client (SSE or REST) treats the HTTP status Edge answers with.
 */
public final class EdgeStatus {
  private EdgeStatus() {
  }

  /**
   * @return true if the status means the api key doesn't exist (or the request can never be right), so retrying will
   * never change it and we must permanently fail. Anything else (401, 403, 429, a 5xx and so on) may well go away.
   */
  public static boolean requestsArePointless(int status) {
    return status == 400 || status == 404;
  }
}
