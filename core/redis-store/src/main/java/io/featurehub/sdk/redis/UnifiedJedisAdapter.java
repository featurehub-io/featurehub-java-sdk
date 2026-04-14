package io.featurehub.sdk.redis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.UnifiedJedis;

import java.util.function.Function;

/**
 * {@link RedisStoreAdapter} backed by {@link UnifiedJedis} (e.g. {@link redis.clients.jedis.JedisPooled}
 * or {@link redis.clients.jedis.JedisCluster}).
 *
 * <p><strong>Note:</strong> {@code UnifiedJedis} does not expose a {@code watch()} method at the
 * public API level, so this adapter uses an optimistic check-then-write strategy rather than true
 * WATCH/MULTI/EXEC atomicity. In a low-contention environment this is usually sufficient; for strict
 * atomicity use {@link JedisPoolAdapter} instead.
 */
class UnifiedJedisAdapter implements RedisStoreAdapter {
  private final UnifiedJedis jedis;

  UnifiedJedisAdapter(@NotNull UnifiedJedis jedis) {
    this.jedis = jedis;
  }

  @Override
  public @Nullable String get(@NotNull String key) {
    return jedis.get(key);
  }

  @Override
  public void set(@NotNull String key, @NotNull String value) {
    jedis.set(key, value);
  }

  /**
   * Optimistic check-then-write: reads current values, computes new values, and writes them
   * without holding a lock. A concurrent writer may overwrite these values between the read and
   * the write; the caller's retry loop in {@link RedisSessionStore} will detect the divergence
   * on the next SHA poll cycle.
   */
  @Override
  public boolean watchedUpdate(
      @NotNull String dataKey,
      @NotNull String shaKey,
      @NotNull Function<String[], String[]> computeNew) {
    String currentData = jedis.get(dataKey);
    String currentSha = jedis.get(shaKey);

    String[] newValues = computeNew.apply(new String[]{currentData, currentSha});
    if (newValues == null) {
      return false;
    }

    jedis.set(dataKey, newValues[0]);
    jedis.set(shaKey, newValues[1]);
    return true;
  }
}
