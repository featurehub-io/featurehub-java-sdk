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

  def "only a 400 or 404 means retrying is pointless"() {
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
      401    | EdgeConnectionState.CONNECTION_FAILURE
      403    | EdgeConnectionState.CONNECTION_FAILURE
      429    | EdgeConnectionState.CONNECTION_FAILURE
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
      tracker.claim(false) != null
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
}
