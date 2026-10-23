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
    // any stream that has opened counts as having worked, one that never opened never does
    tracker = new StreamingConnectionTracker<>(repository, config, retryer, { 0L } as java.util.function.LongSupplier)
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
      !tracker.connectionLost(old, 0, reconnector)
  }

  def "a lost connection we will retry releases those waiting without marking us as failed"() {
    given:
      config.isServerEvaluation() >> true
      def future = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", future).connection
    when:
      tracker.connectionLost(connection, 503, reconnector)
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
      tracker.connectionLost(connection, status, reconnector)
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
      repository.notify(SSEResultState.FAILURE, "streaming") >> { readiness = Readiness.Failed }
      def future = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", future).connection
    when:
      tracker.connectionLost(connection, 404, reconnector)
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
      tracker.connectionLost(connection, 0, reconnector)
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
      1 * repository.notify(SSEResultState.FAILURE, "streaming")
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
      tracker.connectionLost(connection, 0, reconnector)
      connection = tracker.claim(true)
    then:
      connection.lastEventId == null
    when:
      tracker.process(connection, reconnector, "features", "2", "data")
      tracker.connectionLost(connection, 0, reconnector)
    then:
      tracker.claim(true).lastEventId == "2"
  }

  // a connection resumed from the features a previous connection sent, as an up to date client's is
  private StreamingConnectionTracker.Connection<String> resumedConnection() {
    retryer.fromValue("features") >> SSEResultState.FEATURES
    def first = tracker.claim(false)
    tracker.process(first, reconnector, "features", "1", "data")
    tracker.connectionLost(first, 0, reconnector)
    def resumed = tracker.claim(true)
    assert resumed.lastEventId == "1"
    return resumed
  }

  def "a resumed stream that worked without sending features still resets the retryer's backoff when it is lost"() {
    given: "a resumed stream the server is up to date with, which stays open long enough to count as working"
      config.isServerEvaluation() >> true
      def connection = resumedConnection()
      connection.opened()
    when:
      tracker.connectionLost(connection, 0, reconnector)
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
      tracker.connectionLost(connection, 0, reconnector)
    then:
      0 * retryer.edgeResult(EdgeConnectionState.SUCCESS, _)
      1 * retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, reconnector)
  }

  def "a stream that never worked doesn't reset the backoff"() {
    given:
      config.isServerEvaluation() >> true
      def connection = tracker.claim(false)
    when:
      tracker.connectionLost(connection, 0, reconnector)
    then:
      0 * retryer.edgeResult(EdgeConnectionState.SUCCESS, _)
      1 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
  }

  def "resumed streams that stay quiet keep reconnecting straight away, however many there are in a row"() {
    given: "an up to date client behind something that drops idle connections"
      config.isServerEvaluation() >> true
      def connection = resumedConnection()
    when:
      6.times {
        connection.opened()
        tracker.connectionLost(connection, 0, reconnector)
        connection = tracker.claim(true)
      }
    then:
      6 * retryer.edgeResult(EdgeConnectionState.SUCCESS, reconnector)
      6 * retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, reconnector)
      0 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, _)
  }

  def "a stream that isn't resumed and sends nothing never worked, however long it stays open"() {
    given: "e.g. a proxy that accepts the connection and passes nothing through"
      config.isServerEvaluation() >> true
      def connection = tracker.claim(false)
      connection.opened()
    when:
      tracker.connectionLost(connection, 0, reconnector)
    then:
      0 * retryer.edgeResult(EdgeConnectionState.SUCCESS, _)
      1 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
  }

  def "after a context change a stream isn't resumed, so one that sends nothing never worked"() {
    given:
      config.isServerEvaluation() >> true
      resumedConnection()
      def connection = tracker.contextChange("userkey=fred", new CompletableFuture<Readiness>()).connection
      connection.opened()
    when:
      tracker.connectionLost(connection, 0, reconnector)
    then:
      connection.lastEventId == null
      0 * retryer.edgeResult(EdgeConnectionState.SUCCESS, _)
      1 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
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
      tracker.connectionLost(connection, 0, reconnector)
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
      tracker.connectionLost(connection, 0, reconnector)
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
      tracker.connectionLost(connection, 0, reconnector)
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

  def "a reconnect the retryer cancelled after it started doesn't claim a connection"() {
    given: "a lost connection, so a reconnect is pending"
      config.isServerEvaluation() >> true
      tracker.connectionLost(tracker.claim(false), 0, reconnector)
    when:
      def claimed = tracker.claim(true)
    then:
      1 * retryer.isReconnectCancelled() >> true
      claimed == null
  }

  def "an event with empty data is ignored like one with none"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("features") >> SSEResultState.FEATURES
      def waiting = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", waiting).connection
    when:
      tracker.process(connection, reconnector, "features", "7", "")
    then:
      0 * retryer.convertSSEState(_, _, _, _)
      !waiting.done
  }

  def "an event that fails to apply doesn't release those waiting for the context's features"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("features") >> SSEResultState.FEATURES
      retryer.convertSSEState(*_) >> { throw new RuntimeException("JSON failed") }
      def waiting = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", waiting).connection
    when:
      tracker.process(connection, reconnector, "features", "7", "bad-data")
    then:
      !waiting.done
    when: "the connection then goes"
      tracker.connectionLost(connection, 0, reconnector)
    then: "they are released"
      waiting.done
  }

  def "if the retryer throws when told a connection is lost, those waiting are still released and we can connect again"() {
    given:
      config.isServerEvaluation() >> true
      def waiting = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", waiting).connection
      retryer.edgeResult(_, _) >> { throw new java.util.concurrent.RejectedExecutionException("shut down") }
    when:
      def lost = tracker.connectionLost(connection, 0, reconnector)
    then:
      lost
      waiting.done
    and: "no reconnect is coming, so we don't wait for one"
      tracker.claim(false) != null
  }

  def "abandoning a connection closes the retryer, as nothing will reconnect"() {
    given:
      def connection = tracker.claim(false)
    when:
      tracker.abandon(connection)
    then:
      1 * retryer.close()
      tracker.closed
  }

  def "being told we can't have features doesn't make us resume from the failure's id"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("failure") >> SSEResultState.FAILURE
      def waiting = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", waiting).connection
    when:
      tracker.process(connection, reconnector, "failure", "5", null)
      tracker.connectionLost(connection, 0, reconnector)
    then: "those waiting were released"
      waiting.done
    and: "a reconnect isn't resumed"
      tracker.claim(true).lastEventId == null
  }

  def "a stream that isn't resumed and sends only an ack and a bye never worked"() {
    given: "e.g. a proxy or misbehaving Edge that never sends our features"
      config.isServerEvaluation() >> true
      retryer.fromValue("ack") >> SSEResultState.ACK
      retryer.fromValue("bye") >> SSEResultState.BYE
      def connection = tracker.claim(false)
      connection.opened()
      tracker.process(connection, reconnector, "ack", null, null)
      tracker.process(connection, reconnector, "bye", null, null)
    when:
      tracker.connectionLost(connection, 0, reconnector)
    then: "the backoff isn't reset and grows"
      0 * retryer.edgeResult(EdgeConnectionState.SUCCESS, _)
      1 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
  }

  def "an error applying an event has the client drop the connection, after which those waiting are released"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("features") >> SSEResultState.FEATURES
      retryer.convertSSEState(*_) >> { throw new StackOverflowError() }
      def waiting = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", waiting).connection
    when:
      def keep = tracker.process(connection, reconnector, "features", "1", "data")
    then:
      !keep
      !tracker.closed
    when: "the client reports it lost"
      tracker.connectionLost(connection, 0, reconnector)
    then: "it is retried"
      waiting.done
      1 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
  }

  def "a library clash while applying an event gives up for good, as retrying won't fix it"() {
    given:
      config.isServerEvaluation() >> true
      retryer.fromValue("features") >> SSEResultState.FEATURES
      retryer.convertSSEState(*_) >> { throw new NoSuchMethodError("jackson") }
      repository.getReadiness() >> Readiness.NotReady
      def waiting = new CompletableFuture<Readiness>()
      def connection = tracker.contextChange("userkey=fred", waiting).connection
    when:
      def keep = tracker.process(connection, reconnector, "features", "1", "data")
    then:
      !keep
      tracker.closed
      waiting.done
      1 * retryer.close()
      1 * repository.notify(SSEResultState.FAILURE, "streaming")
      0 * retryer.edgeResult(*_)
  }
}
