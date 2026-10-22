package io.featurehub.client.edge

import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.client.Readiness
import io.featurehub.sse.model.SSEResultState
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

class StreamingConnectionTrackerSpec extends Specification {
  InternalFeatureRepository repository
  FeatureHubConfig config
  EdgeRetryService retryer
  EdgeReconnector reconnector
  StreamingConnectionTracker<String> tracker

  def setup() {
    repository = Mock(InternalFeatureRepository)
    config = Mock(FeatureHubConfig)
    retryer = Mock(EdgeRetryService)
    reconnector = Mock(EdgeReconnector)
    tracker = new StreamingConnectionTracker<>(repository, config, retryer)
  }

  def "a client evaluated context never waits, it only claims a connection if there isn't one"() {
    given:
      config.isServerEvaluation() >> false
      def first = new CompletableFuture<Readiness>()
      def second = new CompletableFuture<Readiness>()
    when:
      def firstChange = tracker.contextChange("userkey=fred", first)
      def secondChange = tracker.contextChange("userkey=mary", second)
    then:
      first.done
      second.done
      firstChange.connection != null
      firstChange.connection.header == null // the context is never sent for client evaluated keys
      secondChange.connection == null
      secondChange.previous == null
  }

  def "a server evaluated context waits until its features arrive"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("features") >> SSEResultState.FEATURES
      def future = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", future).connection
      tracker.attach(connection, "fred")
    expect:
      connection.header == "userkey=fred"
      !future.done
    when:
      tracker.process(connection, reconnector, "features", "1", "data")
    then:
      future.done
  }

  def "a changed server evaluated context replaces the old connection, whose events are then ignored"() {
    given:
      config.isServerEvaluation() >> true
      def old = tracker.contextChange("userkey=fred", new CompletableFuture<Readiness>()).connection
      tracker.attach(old, "fred")
    when:
      def change = tracker.contextChange("userkey=mary", new CompletableFuture<Readiness>())
      tracker.process(old, reconnector, "features", "1", "data")
    then:
      change.previous == "fred"
      change.connection.header == "userkey=mary"
      !tracker.isCurrent(old)
      0 * retryer.fromValue(_)
      !tracker.connectionLost(old, 0, 1000, reconnector)
  }

  def "a lost connection we will retry releases those waiting without marking us as failed"() {
    given:
      config.isServerEvaluation() >> true
      def future = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", future).connection
    when:
      tracker.connectionLost(connection, 503, 1000, reconnector)
    then:
      1 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
      _ * repository.getReadiness() >> Readiness.NotReady
      0 * repository.notify(*_)
      future.done
    and: "nothing connects until the retryer reconnects"
      tracker.claim(false) == null
      tracker.claim(true) != null
  }

  def "only a 400, 401, 403 or 404 means retrying is pointless"() {
    given:
      config.isServerEvaluation() >> true
      def connection = tracker.claim(false)
    when:
      tracker.connectionLost(connection, status, 1000, reconnector)
    then:
      1 * retryer.edgeResult(state, reconnector)
    where:
      status | state
      400    | EdgeConnectionState.API_KEY_NOT_FOUND
      404    | EdgeConnectionState.API_KEY_NOT_FOUND
      401    | EdgeConnectionState.FAILURE
      403    | EdgeConnectionState.FAILURE
      429    | EdgeConnectionState.CONNECTION_FAILURE
      503    | EdgeConnectionState.CONNECTION_FAILURE
  }

  def "once the retryer stops, those waiting are told we have failed"() {
    given:
      config.isServerEvaluation() >> true
      def stopped = false
      retryer.isStopped() >> { stopped }
      def readiness = Readiness.NotReady
      repository.getReadiness() >> { readiness }
      repository.notify(SSEResultState.FAILURE) >> { readiness = Readiness.Failed }
      def future = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", future).connection
    when:
      tracker.connectionLost(connection, 404, 1000, reconnector)
    then:
      1 * retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, reconnector) >> { stopped = true }
      future.get() == Readiness.Failed
  }

  def "a failure event with no data is still recorded"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("failure") >> SSEResultState.FAILURE
      def future = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", future).connection
    when:
      tracker.process(connection, reconnector, "failure", null, null)
    then:
      1 * retryer.convertSSEState(SSEResultState.FAILURE, null, repository, _)
      1 * retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, reconnector)
      future.done
  }

  def "a server evaluated context change during a backoff connects straight away and cancels the retryer's reconnect"() {
    given:
      config.isServerEvaluation() >> true
      def connection = tracker.contextChange("userkey=fred", new CompletableFuture<Readiness>()).connection
      tracker.connectionLost(connection, 0, 1000, reconnector)
    when:
      def change = tracker.contextChange("userkey=mary", new CompletableFuture<Readiness>())
    then:
      change.connection != null
      1 * retryer.cancelReconnects()
  }

  def "abandoning a connection marks us failed and releases everyone, without retrying"() {
    given:
      config.isServerEvaluation() >> true
      repository.getReadiness() >> Readiness.NotReady
      def future = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", future).connection
    when:
      tracker.abandon(connection)
    then:
      1 * repository.notify(SSEResultState.FAILURE)
      0 * retryer.edgeResult(*_)
      future.done
      tracker.claim(false) == null
  }

  def "the last event id is only resumed from once we have the context's features"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("ack") >> SSEResultState.ACK
      retryer.fromValue("features") >> SSEResultState.FEATURES
      def connection = tracker.claim(false)
    when:
      tracker.process(connection, reconnector, "ack", "1", null)
      tracker.connectionLost(connection, 0, 1000, reconnector)
      connection = tracker.claim(true)
    then:
      connection.lastEventId == null
    when:
      tracker.process(connection, reconnector, "features", "2", "data")
      tracker.connectionLost(connection, 0, 1000, reconnector)
    then:
      tracker.claim(true).lastEventId == "2"
  }

  def "a stream that worked without sending features still resets the retryer's backoff when it is lost"() {
    given: "a resumed stream the server is up to date with, which stays open long enough to count as working"
      config.isServerEvaluation() >> true
      def connection = tracker.claim(false)
      connection.opened()
    when:
      tracker.connectionLost(connection, 0, 0, reconnector)
    then:
      1 * retryer.edgeResult(EdgeConnectionState.SUCCESS, reconnector)
    then:
      1 * retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, reconnector)
  }

  def "a stream that already reported its features doesn't report success again when it is lost"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("features") >> SSEResultState.FEATURES
      def connection = tracker.claim(false)
      tracker.process(connection, reconnector, "features", "1", "data")
    when:
      tracker.connectionLost(connection, 0, 1000, reconnector)
    then:
      0 * retryer.edgeResult(EdgeConnectionState.SUCCESS, _)
      1 * retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, reconnector)
  }

  def "a stream that never worked doesn't reset the backoff"() {
    given:
      config.isServerEvaluation() >> true
      def connection = tracker.claim(false)
    when:
      tracker.connectionLost(connection, 0, 1000, reconnector)
    then:
      0 * retryer.edgeResult(EdgeConnectionState.SUCCESS, _)
      1 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
  }

  private void loseQuietStream() {
    def connection = tracker.claim(true)
    connection.opened()
    tracker.connectionLost(connection, 0, 0, reconnector)
  }

  def "after several streams in a row that only stayed open, sending nothing, we back off"() {
    given: "something between us and Edge that accepts the connection, sends nothing and drops it once idle"
      config.isServerEvaluation() >> true
    when: "the first few are lost"
      StreamingConnectionTracker.MAX_QUIET_STREAMS.times { loseQuietStream() }
    then: "they count as working"
      StreamingConnectionTracker.MAX_QUIET_STREAMS * retryer.edgeResult(EdgeConnectionState.SUCCESS, reconnector)
      StreamingConnectionTracker.MAX_QUIET_STREAMS * retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, reconnector)
    when: "more are lost"
      loseQuietStream()
      loseQuietStream()
    then: "they are treated as failures, so the backoff grows"
      0 * retryer.edgeResult(EdgeConnectionState.SUCCESS, _)
      2 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
  }

  def "a stream that sends a real event stops us backing off for quiet streams"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("bye") >> SSEResultState.BYE
      (StreamingConnectionTracker.MAX_QUIET_STREAMS + 1).times { loseQuietStream() }
      def speaking = tracker.claim(true)
      speaking.opened()
      tracker.process(speaking, reconnector, "bye", null, null)
      tracker.connectionLost(speaking, 0, 0, reconnector)
    when:
      loseQuietStream()
    then:
      1 * retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, reconnector)
      0 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, _)
  }

  def "a server evaluated context change starts counting quiet streams again"() {
    given:
      config.isServerEvaluation() >> true
      StreamingConnectionTracker.MAX_QUIET_STREAMS.times { loseQuietStream() }
      def connection = tracker.contextChange("userkey=fred", new CompletableFuture<Readiness>()).connection
      connection.opened()
    when:
      tracker.connectionLost(connection, 0, 0, reconnector)
    then:
      1 * retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, reconnector)
      0 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, _)
  }

  def "the reconnect for a lost connection is queued in the same step as it is released, so a context change can't cancel it before it exists"() {
    given: "a context change that tries to run while the lost connection's reconnect is being queued"
      config.isServerEvaluation() >> true
      def connection = tracker.contextChange("userkey=fred", new CompletableFuture<Readiness>()).connection
      Thread changer = null
      def order = Collections.synchronizedList([])
      retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector) >> {
        changer = Thread.start { tracker.contextChange("userkey=mary", new CompletableFuture<Readiness>()) }
        changer.join(200) // can't complete while the reconnect is still being queued
        order.add("queued")
      }
      retryer.cancelReconnects() >> { order.add("cancelled") }
    when:
      tracker.connectionLost(connection, 0, 1000, reconnector)
      changer.join(5000)
    then: "the cancel comes after the reconnect it cancels"
      order == ["queued", "cancelled"]
  }

  def "a failure event from a connection a context change has replaced doesn't stop the retryer or mark us failed"() {
    given: "the old context's connection has started applying a failure event when the context changes"
      config.isServerEvaluation() >> true
      def old = tracker.contextChange("userkey=fred", new CompletableFuture<Readiness>()).connection
      retryer.fromValue("failure") >> {
        tracker.contextChange("userkey=mary", new CompletableFuture<Readiness>())
        SSEResultState.FAILURE
      }
    when:
      tracker.process(old, reconnector, "failure", null, null)
    then:
      0 * retryer.edgeResult(_, _)
      0 * retryer.convertSSEState(_, _, _, _)
  }

  def "a features event with no data is ignored, so it doesn't release callers or become the id we resume from"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("features") >> SSEResultState.FEATURES
      def waiting = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", waiting).connection
    when:
      tracker.process(connection, reconnector, "features", "7", null)
      tracker.connectionLost(connection, 0, 1000, reconnector)
      def next = tracker.claim(true)
    then:
      0 * retryer.convertSSEState(_, _, _, _)
      0 * retryer.edgeResult(EdgeConnectionState.SUCCESS, _)
      next.lastEventId == null
  }

  def "a features event with no data doesn't release those waiting"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("features") >> SSEResultState.FEATURES
      def waiting = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", waiting).connection
    when:
      tracker.process(connection, reconnector, "features", "7", null)
    then:
      !waiting.done
  }

  def "a config event from a connection a context change has replaced doesn't reach the retryer"() {
    given: "the old context's connection has started applying a config event when the context changes"
      config.isServerEvaluation() >> true
      def old = tracker.contextChange("userkey=fred", new CompletableFuture<Readiness>()).connection
      retryer.fromValue("config") >> {
        tracker.contextChange("userkey=mary", new CompletableFuture<Readiness>())
        SSEResultState.CONFIG
      }
    when:
      tracker.process(old, reconnector, "config", null, '{"edge.stale": true}')
    then:
      0 * retryer.edgeConfigInfo(_)
  }

  def "a config event with no data is ignored and doesn't release those waiting"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("config") >> SSEResultState.CONFIG
      def waiting = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", waiting).connection
    when:
      tracker.process(connection, reconnector, "config", null, null)
    then:
      0 * retryer.edgeConfigInfo(_)
      !waiting.done
  }

  def "a bye before the features doesn't release those waiting for them"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("bye") >> SSEResultState.BYE
      def waiting = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", waiting).connection
    when:
      tracker.process(connection, reconnector, "bye", null, null)
    then:
      !waiting.done
    when: "the connection then goes"
      tracker.connectionLost(connection, 0, 0, reconnector)
    then: "they are released"
      waiting.done
  }

  def "once a connection is abandoned we never connect again"() {
    given:
      config.isServerEvaluation() >> true
      def connection = tracker.claim(false)
      tracker.abandon(connection)
    when:
      def later = tracker.claim(false)
      def change = new CompletableFuture<Readiness>()
      def todo = tracker.contextChange("userkey=fred", change)
    then:
      later == null
      todo.connection == null
      change.done
  }
}
