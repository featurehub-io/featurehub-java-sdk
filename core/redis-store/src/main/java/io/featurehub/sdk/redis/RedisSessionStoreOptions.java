package io.featurehub.sdk.redis;

/**
 * Configuration options for {@link RedisSessionStore}.
 */
public class RedisSessionStoreOptions {
  private final String prefix;
  private final long backoffTimeoutMs;
  private final int retryUpdateCount;
  private final int refreshTimeoutSeconds;

  private RedisSessionStoreOptions(Builder builder) {
    this.prefix = builder.prefix;
    this.backoffTimeoutMs = builder.backoffTimeoutMs;
    this.retryUpdateCount = builder.retryUpdateCount;
    this.refreshTimeoutSeconds = builder.refreshTimeoutSeconds;
  }

  public String getPrefix() {
    return prefix;
  }

  /** Milliseconds to sleep between WATCH-contention retries. */
  public long getBackoffTimeoutMs() {
    return backoffTimeoutMs;
  }

  /** Maximum number of times to retry a write that was aborted by WATCH contention. */
  public int getRetryUpdateCount() {
    return retryUpdateCount;
  }

  /** How often (in seconds) to poll Redis for SHA changes and reload features. */
  public int getRefreshTimeoutSeconds() {
    return refreshTimeoutSeconds;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static RedisSessionStoreOptions defaults() {
    return builder().build();
  }

  public static class Builder {
    private String prefix = "featurehub";
    private long backoffTimeoutMs = 500;
    private int retryUpdateCount = 10;
    private int refreshTimeoutSeconds = 300;

    public Builder prefix(String prefix) {
      this.prefix = prefix;
      return this;
    }

    public Builder backoffTimeoutMs(long backoffTimeoutMs) {
      this.backoffTimeoutMs = backoffTimeoutMs;
      return this;
    }

    public Builder retryUpdateCount(int retryUpdateCount) {
      this.retryUpdateCount = retryUpdateCount;
      return this;
    }

    public Builder refreshTimeoutSeconds(int refreshTimeoutSeconds) {
      this.refreshTimeoutSeconds = refreshTimeoutSeconds;
      return this;
    }

    public RedisSessionStoreOptions build() {
      return new RedisSessionStoreOptions(this);
    }
  }
}
