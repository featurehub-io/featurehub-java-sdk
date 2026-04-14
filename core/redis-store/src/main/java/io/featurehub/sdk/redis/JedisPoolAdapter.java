package io.featurehub.sdk.redis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.util.List;
import java.util.function.Function;

/**
 * {@link RedisStoreAdapter} backed by {@link JedisPool}.
 *
 * <p>Uses full WATCH/MULTI/EXEC semantics for atomic conditional updates.
 * {@code JedisPool.getResource()} returns a {@link Jedis} instance which exposes
 * {@code watch()}, {@code multi()}, and {@code Transaction.exec()} that returns {@code null}
 * when the WATCH is violated.
 */
class JedisPoolAdapter implements RedisStoreAdapter {
  private final JedisPool pool;

  JedisPoolAdapter(@NotNull JedisPool pool) {
    this.pool = pool;
  }

  @Override
  public @Nullable String get(@NotNull String key) {
    try (Jedis jedis = pool.getResource()) {
      return jedis.get(key);
    }
  }

  @Override
  public void set(@NotNull String key, @NotNull String value) {
    try (Jedis jedis = pool.getResource()) {
      jedis.set(key, value);
    }
  }

  @Override
  public boolean watchedUpdate(
      @NotNull String dataKey,
      @NotNull String shaKey,
      @NotNull Function<String[], String[]> computeNew) {
    try (Jedis jedis = pool.getResource()) {
      jedis.watch(dataKey, shaKey);

      String currentData = jedis.get(dataKey);
      String currentSha = jedis.get(shaKey);

      String[] newValues = computeNew.apply(new String[]{currentData, currentSha});
      if (newValues == null) {
        jedis.unwatch();
        return false;
      }

      Transaction tx = jedis.multi();
      tx.set(dataKey, newValues[0]);
      tx.set(shaKey, newValues[1]);
      List<Object> result = tx.exec();
      // exec() returns null if WATCH was violated
      return result != null;
    }
  }
}
