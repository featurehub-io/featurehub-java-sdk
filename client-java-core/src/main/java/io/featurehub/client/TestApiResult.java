package io.featurehub.client;

public class TestApiResult {
  private final int code;

  public TestApiResult(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }

  public boolean isSuccess() {
    return code >= 200 && code < 300;
  }

  public boolean isFailed() {
    return code >= 400;
  }

  /**
   * update was accepted but not actioned because feature is already in that state
   */
  public boolean isNotChanged() {
    return code == 200 || code == 202;
  }

  /**
   * update was accepted and actioned
   */
  public boolean isChanged() {
    return code == 201;
  }

  /**
   * you have made a request that doesn't make sense. e.g. it has no data
   */
  public boolean isNonsense() {
    return code == 400;
  }

  /**
   * update was not accepted, attempted change is outside the permissions of this user
   */
  public boolean isNotPermitted() {
    return code == 403;
  }

  /**
   * something about the presented data isn't right and we couldn't find it, could be the service key, the
   * environment or the feature
   */
  public boolean isNonExistant() {
    return code == 404;
  }

  /**
   * you have made a request that isn't possible. e.g. changing a value without unlocking it.
   */
  public boolean isNotPossible() {
    return code == 412;
  }
}
