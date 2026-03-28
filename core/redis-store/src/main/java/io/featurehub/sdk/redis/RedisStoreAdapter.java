package io.featurehub.sdk.redis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Abstraction over Jedis implementations so that {@link RedisSessionStore} is testable
 * without depending on the Jedis class hierarchy.
 *
 * <p>Implementations are package-private.
 */
interface RedisStoreAdapter {

  /**
   * Reads the raw string value for {@code key}, or {@code null} if the key does not exist.
   */
  @Nullable String get(@NotNull String key);

  /**
   * Unconditionally writes {@code value} for {@code key}.
   */
  void set(@NotNull String key, @NotNull String value);

  /**
   * Performs an atomic watched update of two keys.
   *
   * <p>The adapter must:
   * <ol>
   *   <li>WATCH both {@code dataKey} and {@code shaKey}</li>
   *   <li>Call {@code readCurrent} to get the current values of both keys</li>
   *   <li>Call {@code computeNewValues} with the current values; if it returns {@code null}
   *       the adapter must abort without writing anything</li>
   *   <li>Attempt to write the computed values atomically (MULTI/EXEC or equivalent)</li>
   * </ol>
   *
   * @param dataKey       the key that stores the JSON array of FeatureState objects
   * @param shaKey        the key that stores the SHA256 digest
   * @param computeNew    called with the current (dataValue, shaValue) — may return {@code null}
   *                      to abort; otherwise returns the new (dataValue, shaValue) to store
   * @return {@code true} if the write succeeded, {@code false} if it was aborted (WATCH
   *         contention or the compute function returned {@code null})
   */
  boolean watchedUpdate(
      @NotNull String dataKey,
      @NotNull String shaKey,
      @NotNull Function<String[], String[]> computeNew);
}
