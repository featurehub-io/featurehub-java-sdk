package io.featurehub.sdk.redis

import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.javascript.JavascriptObjectMapper
import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import spock.lang.Specification

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Function

class RedisSessionStoreSpec extends Specification {

  RedisStoreAdapter adapter = Mock()
  FeatureHubConfig config = Mock()
  InternalFeatureRepository repo = Mock()
  JavascriptObjectMapper mapper = Mock()
  ScheduledExecutorService scheduler = Mock()

  UUID envId = UUID.randomUUID()
  String dataKey
  String shaKey

  def setup() {
    dataKey = "featurehub_${envId}"
    shaKey  = "featurehub_${envId}_sha"
    config.getEnvironmentId() >> envId
    config.getInternalRepository() >> repo
    repo.execute { Runnable cmd -> cmd.run() }
    repo.getJsonObjectMapper() >> mapper
    // Note: adapter.get(dataKey) is NOT stubbed here — Spock returns null by default,
    // so loadFromRedis() exits early in tests that don't need it.
  }

  /** Builds a store whose scheduler is replaced with the test mock. */
  private RedisSessionStore buildStore(RedisSessionStoreOptions opts = RedisSessionStoreOptions.defaults()) {
    return new RedisSessionStore(adapter, config, opts) {
      @Override
      ScheduledExecutorService buildScheduler() { return scheduler }
    }
  }

  private static FeatureState feature(String key, UUID id, long version,
      FeatureValueType type = FeatureValueType.BOOLEAN) {
    return new FeatureState()
        .id(id)
        .key(key)
        .version(version)
        .type(type)
        .value(type == FeatureValueType.BOOLEAN ? Boolean.TRUE : 'val')
        .l(false)
  }

  // --- startup load ---

  def "constructor does nothing when Redis data key returns null"() {
    // adapter.get(dataKey) returns null by default (no stub needed)
    when:
      buildStore()
    then:
      0 * repo.updateFeatures(_, _)
      1 * repo.registerRawUpdateFeatureListener(_)
      1 * scheduler.scheduleAtFixedRate(_, 300, 300, TimeUnit.SECONDS)
  }

  def "constructor loads features from Redis on startup"() {
    given:
      def id1 = UUID.randomUUID()
      def fs = feature('flag1', id1, 1L)
      def json = '[{"id":"' + id1 + '"}]'
      adapter.get(dataKey) >> json
      mapper.readFeatureStates(json) >> [fs]
    when:
      buildStore()
    then:
      1 * repo.execute {Runnable cmd ->  // 2 for listeners
        cmd.run()
      }
      1 * repo.updateFeatures([fs], RedisSessionStore.SOURCE)
      1 * repo.registerRawUpdateFeatureListener(_)
  }

  def "constructor fails when getInternalRepository returns null"() {
    given:
      // Use a local config mock so we don't conflict with setup()'s stub
      def localConfig = Mock(FeatureHubConfig)
      localConfig.getEnvironmentId() >> envId
      localConfig.getInternalRepository() >> null
    when:
      new RedisSessionStore(adapter, localConfig, RedisSessionStoreOptions.defaults()) {
        @Override ScheduledExecutorService buildScheduler() { return scheduler }
      }
    then:
      thrown(RuntimeException)
  }

  def "constructor schedules refresh with the configured interval"() {
    given:
      def opts = RedisSessionStoreOptions.builder().refreshTimeoutSeconds(60).build()
    when:
      buildStore(opts)
    then:
      1 * repo.registerRawUpdateFeatureListener(_)
      1 * scheduler.scheduleAtFixedRate(_, 60, 60, TimeUnit.SECONDS)
  }

  // --- updateFeatures (batch) ---

  def "updateFeatures ignores updates from redis-store source"() {
    given:
      def store = buildStore()
    when:
      store.updateFeatures([feature('f', UUID.randomUUID(), 1L)], RedisSessionStore.SOURCE)
    then:
      0 * adapter.watchedUpdate(_, _, _)
  }

  def "updateFeatures stores new features to Redis"() {
    given:
      def store = buildStore()
      def id1 = UUID.randomUUID()
      def incoming = feature('flag1', id1, 2L)
      mapper.readFeatureStates(null) >> []
      mapper.writeValueAsString(_) >> '[]'
    when:
      store.updateFeatures([incoming], 'edge')
    then:
      1 * adapter.watchedUpdate(dataKey, shaKey, _) >> { String dk, String sk, Function fn ->
        fn.apply([null, null] as String[])
        return true
      }
      _ * adapter.get(shaKey) >> 'newsha'
  }

  def "updateFeatures prefers higher version over existing feature in Redis"() {
    given:
      def store = buildStore()
      def id1 = UUID.randomUUID()
      def existing = feature('flag1', id1, 1L)
      def incoming = feature('flag1', id1, 3L)
      def existingJson = '[{"id":"' + id1 + '"}]'
      mapper.readFeatureStates(existingJson) >> [existing]
      mapper.writeValueAsString(_) >> '[]'
    when:
      store.updateFeatures([incoming], 'edge')
    then:
      1 * adapter.watchedUpdate(dataKey, shaKey, _) >> { String dk, String sk, Function fn ->
        String[] result = fn.apply([existingJson, 'oldsha'] as String[])
        assert result != null
        return true
      }
      _ * adapter.get(shaKey) >> 'newsha'
  }

  def "updateFeatures aborts without retry when no incoming feature is newer"() {
    given:
      def store = buildStore()
      def id1 = UUID.randomUUID()
      def existing = feature('flag1', id1, 5L)
      def incoming = feature('flag1', id1, 2L)   // lower version
      def existingJson = '[{"id":"' + id1 + '"}]'
      mapper.readFeatureStates(existingJson) >> [existing]
    when:
      store.updateFeatures([incoming], 'edge')
    then:
      // Exactly 1 call — no retry because the merger signalled "nothing to write"
      1 * adapter.watchedUpdate(dataKey, shaKey, _) >> { String dk, String sk, Function fn ->
        String[] result = fn.apply([existingJson, 'sha'] as String[])
        assert result == null
        return false
      }
  }

  // --- updateFeature (single) ---

  def "updateFeature ignores updates from redis-store source"() {
    given:
      def store = buildStore()
    when:
      store.updateFeature(feature('f', UUID.randomUUID(), 1L), RedisSessionStore.SOURCE)
    then:
      0 * adapter.watchedUpdate(_, _, _)
  }

  def "updateFeature adds new feature when not already in Redis"() {
    given:
      def store = buildStore()
      def id1 = UUID.randomUUID()
      def incoming = feature('flag1', id1, 1L)
      mapper.readFeatureStates(_) >> []
      mapper.writeValueAsString(_) >> '[]'
    when:
      store.updateFeature(incoming, 'edge')
    then:
      1 * adapter.watchedUpdate(dataKey, shaKey, _) >> { String dk, String sk, Function fn ->
        String[] result = fn.apply([null, null] as String[])
        assert result != null
        return true
      }
      _ * adapter.get(shaKey) >> 'newsha'
  }

  def "updateFeature replaces existing feature when incoming version is higher"() {
    given:
      def store = buildStore()
      def id1 = UUID.randomUUID()
      def existing = feature('flag1', id1, 1L)
      def incoming = feature('flag1', id1, 2L)
      def existingJson = '[{"id":"' + id1 + '"}]'
      mapper.readFeatureStates(existingJson) >> [existing]
      mapper.writeValueAsString(_) >> '[]'
    when:
      store.updateFeature(incoming, 'edge')
    then:
      1 * adapter.watchedUpdate(dataKey, shaKey, _) >> { String dk, String sk, Function fn ->
        String[] result = fn.apply([existingJson, 'sha'] as String[])
        assert result != null
        return true
      }
      _ * adapter.get(shaKey) >> 'newsha'
  }

  def "updateFeature aborts without retry when incoming version is not higher"() {
    given:
      def store = buildStore()
      def id1 = UUID.randomUUID()
      def existing = feature('flag1', id1, 3L)
      def incoming = feature('flag1', id1, 1L)
      def existingJson = '[{"id":"' + id1 + '"}]'
      mapper.readFeatureStates(existingJson) >> [existing]
    when:
      store.updateFeature(incoming, 'edge')
    then:
      // Exactly 1 call — merger aborts, no retry
      1 * adapter.watchedUpdate(dataKey, shaKey, _) >> { String dk, String sk, Function fn ->
        String[] result = fn.apply([existingJson, 'sha'] as String[])
        assert result == null
        return false
      }
  }

  // --- deleteFeature ---

  def "deleteFeature ignores updates from redis-store source"() {
    given:
      def store = buildStore()
    when:
      store.deleteFeature(feature('f', UUID.randomUUID(), 1L), RedisSessionStore.SOURCE)
    then:
      0 * adapter.watchedUpdate(_, _, _)
  }

  def "deleteFeature removes feature by id"() {
    given:
      def store = buildStore()
      def id1 = UUID.randomUUID()
      def existing = feature('flag1', id1, 1L)
      def toDelete = feature('flag1', id1, 1L)
      def existingJson = '[{"id":"' + id1 + '"}]'
      mapper.readFeatureStates(existingJson) >> [existing]
      mapper.writeValueAsString([]) >> '[]'
    when:
      store.deleteFeature(toDelete, 'edge')
    then:
      1 * adapter.watchedUpdate(dataKey, shaKey, _) >> { String dk, String sk, Function fn ->
        String[] result = fn.apply([existingJson, 'sha'] as String[])
        assert result != null
        return true
      }
      _ * adapter.get(shaKey) >> 'newsha'
  }

  def "deleteFeature does nothing when feature id is not found"() {
    given:
      def store = buildStore()
      def id1 = UUID.randomUUID()
      def otherId = UUID.randomUUID()
      def existing = feature('flag1', id1, 1L)
      def toDelete = feature('other', otherId, 1L)
      def existingJson = '[{"id":"' + id1 + '"}]'
      mapper.readFeatureStates(existingJson) >> [existing]
    when:
      store.deleteFeature(toDelete, 'edge')
    then:
      // Merger aborts (not found) → no retry
      1 * adapter.watchedUpdate(dataKey, shaKey, _) >> { String dk, String sk, Function fn ->
        String[] result = fn.apply([existingJson, 'sha'] as String[])
        assert result == null
        return false
      }
  }

  // --- retry ---

  def "storeWithRetry retries on WATCH contention and succeeds on third attempt"() {
    given:
      def opts = RedisSessionStoreOptions.builder().retryUpdateCount(3).backoffTimeoutMs(1).build()
      def store = buildStore(opts)
      mapper.readFeatureStates(_) >> []
      mapper.writeValueAsString(_) >> '[]'
    when:
      store.updateFeature(feature('flag', UUID.randomUUID(), 1L), 'edge')
    then:
      // watchedUpdate is called 3 times; the function IS invoked on each call to simulate real WATCH behavior
      3 * adapter.watchedUpdate(dataKey, shaKey, _) >> { String dk, String sk, Function fn ->
        fn.apply([null, null] as String[])  // function returns new values (not null)
        return false  // but WATCH says "contention — try again"
      } >> { String dk, String sk, Function fn ->
        fn.apply([null, null] as String[])
        return false
      } >> { String dk, String sk, Function fn ->
        fn.apply([null, null] as String[])
        return true   // success on third attempt
      }
      _ * adapter.get(shaKey) >> 'newsha'
  }

  def "storeWithRetry gives up after retryUpdateCount exhausted"() {
    given:
      def opts = RedisSessionStoreOptions.builder().retryUpdateCount(3).backoffTimeoutMs(1).build()
      def store = buildStore(opts)
      mapper.readFeatureStates(_) >> []
      mapper.writeValueAsString(_) >> '[]'
    when:
      store.updateFeature(feature('flag', UUID.randomUUID(), 1L), 'edge')
    then:
      3 * adapter.watchedUpdate(dataKey, shaKey, _) >> { String dk, String sk, Function fn ->
        fn.apply([null, null] as String[])
        return false
      }
  }

  // --- periodic refresh ---

  def "refreshIfChanged does nothing when SHA matches current"() {
    given:
      def id1 = UUID.randomUUID()
      def fs = feature('f', id1, 1L)
      def json = '[{"id":"' + id1 + '"}]'
      // Prime the store so currentSha is set
      repo.execute(_ as Runnable) >> { Runnable cmd -> cmd.run() }
      adapter.get(dataKey) >> json
      mapper.readFeatureStates(json) >> [fs]
      def store = buildStore()
      def sha = RedisSessionStore.computeSha([fs])
      // Now refreshIfChanged will see the same SHA
      adapter.get(shaKey) >> sha
    when:
      store.refreshIfChanged()
    then:
      0 * repo.updateFeatures(_, _)
  }

  def "refreshIfChanged reloads features when SHA differs from current"() {
    given:
      // Build store with empty Redis (no features loaded)
      def store = buildStore()
      def id1 = UUID.randomUUID()
      def fs = feature('flag1', id1, 2L)
      def json = '[{"id":"' + id1 + '"}]'
      adapter.get(shaKey) >> 'differentsha'
      adapter.get(dataKey) >> json
      mapper.readFeatureStates(json) >> [fs]
    when:
      store.refreshIfChanged()
    then:
      1 * repo.updateFeatures([fs], RedisSessionStore.SOURCE)
  }

  def "refreshIfChanged does nothing when Redis SHA key is null"() {
    given:
      def store = buildStore()
      adapter.get(shaKey) >> null
    when:
      store.refreshIfChanged()
    then:
      0 * repo.updateFeatures(_, _)
  }

  def "refreshIfChanged does nothing when data key is empty after SHA change"() {
    given:
      def store = buildStore()
      adapter.get(shaKey) >> 'newsha'
      // adapter.get(dataKey) still returns null (Spock default)
    when:
      store.refreshIfChanged()
    then:
      0 * repo.updateFeatures(_, _)
  }

  // --- close ---

  def "close shuts down the scheduler"() {
    given:
      def store = buildStore()
    when:
      store.close()
    then:
      1 * scheduler.shutdownNow()
  }
}
