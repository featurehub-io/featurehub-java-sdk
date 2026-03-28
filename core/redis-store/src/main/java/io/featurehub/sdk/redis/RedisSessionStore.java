package io.featurehub.sdk.redis;

import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.RawUpdateFeatureListener;
import io.featurehub.sse.model.FeatureState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.UnifiedJedis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Backs a FeatureHub SDK repository with Redis.
 *
 * <p>On construction the store reads whatever features are already in Redis and pushes them into
 * the repository (source {@value SOURCE}).  Any subsequent updates pushed into the repository by
 * the normal edge connection are intercepted via {@link RawUpdateFeatureListener} and written back
 * to Redis so that the next process start-up restores them.
 *
 * <p>A background thread polls the SHA key every {@code options.refreshTimeoutSeconds} seconds.
 * When it detects that the SHA stored in Redis differs from the last SHA we wrote, it reloads the
 * full feature list from Redis and pushes it into the repository, allowing multiple SDK instances
 * sharing the same Redis keys to stay in sync.
 *
 * <h2>Redis key layout</h2>
 * <pre>
 *   {@code <prefix>_<environmentId>}      — JSON array of FeatureState objects
 *   {@code <prefix>_<environmentId>_sha}  — SHA-256 of the sorted "id:version" pairs
 * </pre>
 *
 * <h2>Atomicity</h2>
 * Updates use WATCH/MULTI/EXEC (via {@link JedisPoolAdapter}) or an optimistic check-then-write
 * (via {@link UnifiedJedisAdapter}) and retry up to {@code options.retryUpdateCount} times before
 * giving up.
 */
public class RedisSessionStore implements RawUpdateFeatureListener {
  private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);

  static final String SOURCE = "redis-store";

  private final RedisStoreAdapter adapter;
  private final FeatureHubConfig config;
  private final RedisSessionStoreOptions options;
  private final String dataKey;
  private final String shaKey;
  private volatile String currentSha;
  private final ScheduledExecutorService scheduler;

  // --- public constructors ---

  public RedisSessionStore(@NotNull JedisPool pool, @NotNull FeatureHubConfig config) {
    this(pool, config, RedisSessionStoreOptions.defaults());
  }

  public RedisSessionStore(
      @NotNull JedisPool pool,
      @NotNull FeatureHubConfig config,
      @NotNull RedisSessionStoreOptions options) {
    this(new JedisPoolAdapter(pool), config, options);
  }

  public RedisSessionStore(@NotNull UnifiedJedis jedis, @NotNull FeatureHubConfig config) {
    this(jedis, config, RedisSessionStoreOptions.defaults());
  }

  public RedisSessionStore(
      @NotNull UnifiedJedis jedis,
      @NotNull FeatureHubConfig config,
      @NotNull RedisSessionStoreOptions options) {
    this(new UnifiedJedisAdapter(jedis), config, options);
  }

  /** Package-private constructor used by tests to inject a mock adapter. */
  RedisSessionStore(
      @NotNull RedisStoreAdapter adapter,
      @NotNull FeatureHubConfig config,
      @NotNull RedisSessionStoreOptions options) {
    this.adapter = adapter;
    this.config = config;
    this.options = options;

    UUID environmentId = config.getEnvironmentId();
    this.dataKey = options.getPrefix() + "_" + environmentId;
    this.shaKey = options.getPrefix() + "_" + environmentId + "_sha";

    loadFromRedis();

    InternalFeatureRepository repo = config.getInternalRepository();
    if (repo != null) {
      repo.registerRawUpdateFeatureListener(this);
    }

    this.scheduler = buildScheduler();
    this.scheduler.scheduleAtFixedRate(
        this::refreshIfChanged,
        options.getRefreshTimeoutSeconds(),
        options.getRefreshTimeoutSeconds(),
        TimeUnit.SECONDS);
  }

  // visible for testing
  ScheduledExecutorService buildScheduler() {
    ScheduledExecutorService svc = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "redis-store-refresh");
      t.setDaemon(true);
      return t;
    });
    return svc;
  }

  // --- startup load ---

  private void loadFromRedis() {
    String json = adapter.get(dataKey);
    if (json == null || json.isEmpty()) {
      return;
    }

    InternalFeatureRepository repo = config.getInternalRepository();
    if (repo == null) {
      return;
    }

    List<FeatureState> features = parseFeatures(json, repo);
    if (features == null || features.isEmpty()) {
      return;
    }

    currentSha = computeSha(features);
    log.debug("Loaded {} feature(s) from Redis key '{}'", features.size(), dataKey);
    repo.updateFeatures(features, SOURCE);
  }

  // --- periodic refresh ---

  void refreshIfChanged() {
    try {
      String redisSha = adapter.get(shaKey);
      if (redisSha == null || redisSha.equals(currentSha)) {
        return;
      }

      String json = adapter.get(dataKey);
      if (json == null || json.isEmpty()) {
        return;
      }

      InternalFeatureRepository repo = config.getInternalRepository();
      if (repo == null) {
        return;
      }

      List<FeatureState> features = parseFeatures(json, repo);
      if (features == null || features.isEmpty()) {
        return;
      }

      currentSha = redisSha;
      log.debug("SHA changed — reloading {} feature(s) from Redis", features.size());
      repo.updateFeatures(features, SOURCE);
    } catch (Exception e) {
      log.warn("Error during Redis refresh poll", e);
    }
  }

  // --- RawUpdateFeatureListener ---

  @Override
  public void updateFeatures(@NotNull List<FeatureState> features, @NotNull String source) {
    if (SOURCE.equals(source)) return;
    storeWithRetry(new BulkUpdateMerger(features));
  }

  @Override
  public void updateFeature(@NotNull FeatureState feature, @NotNull String source) {
    if (SOURCE.equals(source)) return;
    storeWithRetry(new SingleUpdateMerger(feature));
  }

  @Override
  public void deleteFeature(@NotNull FeatureState feature, @NotNull String source) {
    if (SOURCE.equals(source)) return;
    storeWithRetry(new DeleteMerger(feature));
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }

  // --- retry loop ---

  private void storeWithRetry(Merger merger) {
    for (int attempt = 0; attempt < options.getRetryUpdateCount(); attempt++) {
      // mergerAborted[0] is set to true inside the lambda when the merger returns null
      // (meaning there is nothing to write), so we can bail out without retrying.
      boolean[] mergerAborted = {false};

      boolean success = adapter.watchedUpdate(dataKey, shaKey, current -> {
        InternalFeatureRepository repo = config.getInternalRepository();
        if (repo == null) return null;

        List<FeatureState> existing = parseFeatures(current[0], repo);
        if (existing == null) existing = new ArrayList<>();

        List<FeatureState> merged = merger.merge(existing);
        if (merged == null) {
          // merger signalled: nothing to write — mark so the retry loop exits
          mergerAborted[0] = true;
          return null;
        }

        String newSha = computeSha(merged);
        String newJson = serializeFeatures(merged, repo);
        if (newJson == null) return null;

        return new String[]{newJson, newSha};
      });

      if (success) {
        // update our local SHA so the refresh loop doesn't immediately reload
        String sha = adapter.get(shaKey);
        if (sha != null) currentSha = sha;
        return;
      }

      if (mergerAborted[0]) {
        // nothing to write — done
        return;
      }

      // WATCH contention — sleep and retry
      if (attempt < options.getRetryUpdateCount() - 1) {
        try {
          Thread.sleep(options.getBackoffTimeoutMs());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
    log.warn("Failed to write to Redis after {} attempts (WATCH contention?)", options.getRetryUpdateCount());
  }

  // --- mergers ---

  private interface Merger {
    /** Returns the new list to store, or {@code null} to abort. */
    @Nullable List<FeatureState> merge(@NotNull List<FeatureState> existing);
  }

  /** Merges a batch of incoming features: keep the highest version per id. */
  private static class BulkUpdateMerger implements Merger {
    private final List<FeatureState> incoming;

    BulkUpdateMerger(List<FeatureState> incoming) {
      this.incoming = incoming;
    }

    @Override
    public @Nullable List<FeatureState> merge(@NotNull List<FeatureState> existing) {
      List<FeatureState> result = new ArrayList<>(existing);
      boolean anyChange = false;

      for (FeatureState fs : incoming) {
        int idx = findById(result, fs.getId());
        if (idx < 0) {
          result.add(fs);
          anyChange = true;
        } else {
          FeatureState cur = result.get(idx);
          if (versionOf(fs) > versionOf(cur)) {
            result.set(idx, fs);
            anyChange = true;
          }
        }
      }

      return anyChange ? result : null;
    }
  }

  /** Merges a single incoming feature: replaces if incoming version is higher. */
  private static class SingleUpdateMerger implements Merger {
    private final FeatureState incoming;

    SingleUpdateMerger(FeatureState incoming) {
      this.incoming = incoming;
    }

    @Override
    public @Nullable List<FeatureState> merge(@NotNull List<FeatureState> existing) {
      List<FeatureState> result = new ArrayList<>(existing);
      int idx = findById(result, incoming.getId());
      if (idx < 0) {
        result.add(incoming);
        return result;
      }
      FeatureState cur = result.get(idx);
      if (versionOf(incoming) > versionOf(cur)) {
        result.set(idx, incoming);
        return result;
      }
      return null;
    }
  }

  /** Removes a feature by id. */
  private static class DeleteMerger implements Merger {
    private final FeatureState toDelete;

    DeleteMerger(FeatureState toDelete) {
      this.toDelete = toDelete;
    }

    @Override
    public @Nullable List<FeatureState> merge(@NotNull List<FeatureState> existing) {
      int idx = findById(existing, toDelete.getId());
      if (idx < 0) return null;
      List<FeatureState> result = new ArrayList<>(existing);
      result.remove(idx);
      return result;
    }
  }

  // --- helpers ---

  private static int findById(List<FeatureState> list, UUID id) {
    for (int i = 0; i < list.size(); i++) {
      if (id.equals(list.get(i).getId())) return i;
    }
    return -1;
  }

  private static long versionOf(FeatureState fs) {
    return fs.getVersion() == null ? 0L : fs.getVersion();
  }

  @Nullable
  private static List<FeatureState> parseFeatures(
      @Nullable String json, @NotNull InternalFeatureRepository repo) {
    if (json == null || json.isEmpty()) return null;
    try {
      return repo.getJsonObjectMapper().readFeatureStates(json);
    } catch (Exception e) {
      log.warn("Failed to parse feature JSON from Redis", e);
      return null;
    }
  }

  @Nullable
  private static String serializeFeatures(
      @NotNull List<FeatureState> features, @NotNull InternalFeatureRepository repo) {
    try {
      return repo.getJsonObjectMapper().writeValueAsString(features);
    } catch (Exception e) {
      log.warn("Failed to serialize features for Redis", e);
      return null;
    }
  }

  /**
   * Computes a SHA-256 digest from the sorted {@code id:version} pairs for the given feature list.
   * Features are sorted by {@code id} (UUID string) so the result is deterministic regardless of
   * insertion order.  A {@code null} version is treated as {@code 0}.
   */
  static String computeSha(List<FeatureState> features) {
    List<FeatureState> sorted = new ArrayList<>(features);
    sorted.sort(Comparator.comparing(fs -> fs.getId().toString()));

    StringBuilder sb = new StringBuilder();
    for (FeatureState fs : sorted) {
      if (sb.length() > 0) sb.append('|');
      sb.append(fs.getId()).append(':').append(versionOf(fs));
    }

    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b & 0xFF));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }
}
