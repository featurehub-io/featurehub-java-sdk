package io.featurehub.okhttp


import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.client.Readiness
import io.featurehub.client.edge.EdgeConnectionState
import io.featurehub.client.edge.EdgeRetryService
import io.featurehub.sse.model.SSEResultState
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import spock.lang.Specification

class SSEClientSpec extends Specification {
  EventSource mockEventSource
  EdgeRetryService retry
  InternalFeatureRepository repository
  FeatureHubConfig config
  EventSourceListener esListener
  SSEClient client
  Request request

  def setup() {
    mockEventSource = Mock(EventSource)
    retry = Mock(EdgeRetryService)
    repository = Mock(InternalFeatureRepository)
    config = Mock(FeatureHubConfig)
    config.realtimeUrl >> "http://special"

    client = new SSEClient(repository, config, retry) {
      @Override
      protected EventSource makeEventSource(Request req, EventSourceListener listener) {
        esListener = listener
        request = req
        return mockEventSource
      }
    }
  }

  def "success lifecycle"() {
    when: "i poll"
      client.poll()
      esListener.onEvent(mockEventSource, '1', "features", "sausage")
    then:
      1 * repository.notify(SSEResultState.FEATURES, "sausage")
      1 * retry.edgeResult(EdgeConnectionState.SUCCESS, client)
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
  }

  def "success then bye but not close lifecycle"() {
    when: "i poll"
      client.poll()
      esListener.onEvent(mockEventSource, '1', "features", "sausage")
      esListener.onEvent(mockEventSource, '1', "bye", "sausage")

    then:
      1 * repository.notify(SSEResultState.FEATURES, "sausage")
      1 * repository.notify(SSEResultState.BYE, "sausage")
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      1 * retry.fromValue('bye') >> SSEResultState.BYE
      1 * retry.edgeResult(EdgeConnectionState.SUCCESS, client)
      0 * retry.edgeResult(EdgeConnectionState.SERVER_SAID_BYE, client)
  }

  def "success then bye then close lifecycle"() {
    when: "i poll"
      client.poll()
      esListener.onEvent(mockEventSource, '1', "features", "sausage")
      esListener.onEvent(mockEventSource, '1', "bye", "sausage")
      esListener.onClosed(mockEventSource)
    then:
      1 * repository.notify(SSEResultState.FEATURES, "sausage")
      1 * repository.notify(SSEResultState.BYE, "sausage")
      1 * retry.edgeResult(EdgeConnectionState.SUCCESS, client)
      1 * retry.edgeResult(EdgeConnectionState.SERVER_SAID_BYE, client)
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      1 * retry.fromValue('bye') >> SSEResultState.BYE
      1 * repository.notify(SSEResultState.FAILURE, null)
      1 * repository.readyness >> Readiness.NotReady
  }

  def "success then close with no bye"() {
    when: "i poll"
      client.poll()
      esListener.onEvent(mockEventSource, '1', "features", "sausage")
      esListener.onClosed(mockEventSource)
    then:
      1 * repository.notify(SSEResultState.FEATURES, "sausage")
      1 * retry.edgeResult(EdgeConnectionState.SUCCESS, client)
      1 * retry.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
      1 * repository.notify(SSEResultState.FAILURE, null)
      1 * repository.readyness >> Readiness.NotReady
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
  }

  def "open then immediate failure"() {
    when: "i poll"
      client.poll()
//      esListener.onOpen(mockEventSource, Mock(Response))
      esListener.onFailure(mockEventSource, null, null)
    then:
      1 * repository.readyness >> Readiness.NotReady
      1 * repository.notify(SSEResultState.FAILURE, null)
      1 * retry.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
  }

  def "when i context change with a client side key, it gives me a future which resolves readyness"() {
    when: "i change context"
      def future = client.contextChange("header", '0')
      esListener.onEvent(mockEventSource, "1", "features", "data")
    then:
      1 * repository.notify(SSEResultState.FEATURES, "data")
      1 * repository.readyness >> Readiness.Failed
      1 * retry.edgeResult(EdgeConnectionState.SUCCESS, client)
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      future.get() == Readiness.Failed
  }

  def "when i context change with a server side key, it creates a request with the header"() {
    when: "i change context"
      def future = client.contextChange("header", '0')
    then:
      1 * config.serverEvaluation >> true
      request.header("x-featurehub") == "header"
  }

  def "when i change context twice with a server side key, it cancels the existing event source and no incoming data means futures are not ready"() {
    when: "i change context"
      def future1 = client.contextChange("header", '0')
    and: "i change context again"
      def future2 = client.contextChange("header2", '0')
    then:
      2 * config.serverEvaluation >> true
      1 * mockEventSource.cancel()
      request.header("x-featurehub") == "header2"
      !future1.done
      !future2.done
  }

  def "when i change context twice with a server side key, and then results come in completes both futures"() {
    when: "i change context"
      def future1 = client.contextChange("header", '0')
    and: "i change context again"
      def future2 = client.contextChange("header2", '0')
    and: "i resolve the incoming call"
      esListener.onEvent(mockEventSource, '1', 'features', 'data')
    then:
      2 * config.serverEvaluation >> true
      2 * repository.readyness >> Readiness.Ready
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      request.header("x-featurehub") == "header2"
      future1.done
      future2.done
      future1.get() == Readiness.Ready
      future2.get() == Readiness.Ready
  }

  def "when config says client evaluated code, this will echo"() {
    when: "i check server vs client"
      def clientSide = client.clientEvaluation
    then:
      1 * config.serverEvaluation >> false
      clientSide
  }

  def "when config says server evaluated code, this will echo"() {
    when: "i check server vs client"
      def clientSide = client.clientEvaluation
    then:
      1 * config.serverEvaluation >> true
      !clientSide
  }

  def "config in is config out"() {
    when: "i get the config"
      def cfg = client.config
    then:
      cfg == config
  }

  def "replacement is not required for this API, it can handle swapping SSE clients"() {
    when: "i ask if it can swap headers"
      def swap = client.requiresReplacementOnHeaderChange
    then:
      !swap
  }
}
