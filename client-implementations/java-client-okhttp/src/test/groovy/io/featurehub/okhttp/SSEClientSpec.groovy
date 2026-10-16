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
  UUID envId
  int connections
  Closure onMakeEventSource

  def setup() {
    mockEventSource = Mock(EventSource)
    retry = Mock(EdgeRetryService)
    repository = Mock(InternalFeatureRepository)
    envId = UUID.randomUUID()
    config = Mock(FeatureHubConfig)
    config.realtimeUrl >> "http://special"

    client = new SSEClient(repository, config, retry) {
      @Override
      protected EventSource makeEventSource(Request req, EventSourceListener listener) {
        println("returning mock event source")
        esListener = listener
        request = req
        connections++
        onMakeEventSource?.call()
        return mockEventSource
      }
    }
  }

  def "success lifecycle"() {
    when: "i poll"
      client.poll()
      esListener.onEvent(mockEventSource, '1', "features", "sausage")
    then:
      1 * config.getRealtimeUrl() >> "http://localhost"
      1 * retry.fromValue('features') >> SSEResultState.FEATURES // converts the "type" field
      1 * retry.convertSSEState(SSEResultState.FEATURES, "sausage", repository, envId)
      1 * repository.getReadiness() >> Readiness.Ready
      1 * retry.edgeResult(EdgeConnectionState.SUCCESS, client)
      1 * config.getEnvironmentId() >> envId
      _ * retry.isStopped()
      0 * _
  }

  def "success then bye but not close lifecycle"() {
    when: "i poll"
      client.poll()
      esListener.onEvent(mockEventSource, '1', "features", "sausage")
      esListener.onEvent(mockEventSource, '1', "bye", "sausage")

    then:
      1 * config.getRealtimeUrl() >> "http://localhost"
      1 * retry.convertSSEState(SSEResultState.FEATURES, "sausage", repository,envId)
      1 * retry.convertSSEState(SSEResultState.BYE, "sausage", repository,envId)
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      1 * retry.fromValue('bye') >> SSEResultState.BYE
      1 * repository.getReadiness() >> Readiness.Ready
      1 * retry.edgeResult(EdgeConnectionState.SUCCESS, client)
      2 * config.getEnvironmentId() >> envId
      0 * retry.edgeResult(EdgeConnectionState.SERVER_SAID_BYE, client)
      _ * retry.isStopped()
      0 * _
  }

  def "success then bye then close lifecycle"() {
    when: "i poll"
      client.poll()
      esListener.onEvent(mockEventSource, '1', "features", "sausage")
      esListener.onEvent(mockEventSource, '1', "bye", "sausage")
      esListener.onClosed(mockEventSource)
    then:
      1 * config.getRealtimeUrl() >> "http://localhost"
      1 * retry.convertSSEState(SSEResultState.FEATURES, "sausage", repository,envId)
      1 * retry.convertSSEState(SSEResultState.BYE, "sausage", repository,envId)
      1 * retry.edgeResult(EdgeConnectionState.SUCCESS, client)
      1 * retry.edgeResult(EdgeConnectionState.SERVER_SAID_BYE, client)
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      1 * retry.fromValue('bye') >> SSEResultState.BYE
      2 * repository.getReadiness() >> Readiness.NotReady
      1 * repository.notify(SSEResultState.FAILURE)
      2 * config.getEnvironmentId() >> envId
      _ * retry.isStopped()
      0 * _
  }

  def "success then close with no bye"() {
    when: "i poll"
      client.poll()
      esListener.onEvent(mockEventSource, '1', "features", "sausage")
      esListener.onClosed(mockEventSource)
    then:
      1 * config.getRealtimeUrl() >> "http://localhost"
      1 * retry.convertSSEState(SSEResultState.FEATURES, "sausage", repository,envId)
      1 * retry.edgeResult(EdgeConnectionState.SUCCESS, client)
      1 * retry.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
      1 * repository.notify(SSEResultState.FAILURE)
      2 * repository.getReadiness() >> Readiness.NotReady
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      1 * config.getEnvironmentId() >> envId
      _ * retry.isStopped()
      0 * _
  }

  def "open then immediate failure"() {
    when: "i poll"
      client.poll()
//      esListener.onOpen(mockEventSource, Mock(Response))
      esListener.onFailure(mockEventSource, null, null)
    then:
      1 * config.getRealtimeUrl() >> "http://localhost"
      1 * config.baseUrl() >> "http://localhost" // used by trace log
      3 * repository.getReadiness() >> Readiness.NotReady
      1 * repository.notify(SSEResultState.FAILURE)
      1 * retry.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
      _ * retry.isStopped()
      0 * _
  }

  def "when i context change with a client side key, it gives me a future which resolves readyness"() {
    when: "i change context"
      def future = client.contextChange("header", '0')
      esListener.onEvent(mockEventSource, "1", "features", "data")
    then:
      1 * config.getRealtimeUrl() >> "http://localhost"
      1 * retry.convertSSEState(SSEResultState.FEATURES, "data", repository,envId)
      1 * config.isServerEvaluation() >> false
      1 * repository.getReadiness() >> Readiness.Failed
      1 * retry.edgeResult(EdgeConnectionState.SUCCESS, client)
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      future.get() == Readiness.Failed
      1 * config.getEnvironmentId() >> envId
      _ * retry.isStopped()
      0 * _
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
      2 * repository.getReadiness() >> Readiness.Ready
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      request.header("x-featurehub") == "header2"
      future1.done
      future2.done
      future1.get() == Readiness.Ready
      future2.get() == Readiness.Ready
  }

  def "the last event id is sent when reconnecting and is kept when an event has no id"() {
    given: "the first connection has no last event id"
      client.poll()
      assert request.header("Last-Event-ID") == null
    when: "we receive an event with an id, then one without, then the server disconnects and we reconnect"
      esListener.onEvent(mockEventSource, '5', 'ack', 'data')
      esListener.onEvent(mockEventSource, null, 'ack', 'data')
      esListener.onClosed(mockEventSource)
      client.reconnect()
    then:
      request.header("Last-Event-ID") == "5"
  }

  def "a change in the server evaluated context clears the last event id"() {
    given: "we are connected with one context and have received an event with an id"
      config.serverEvaluation >> true
      client.contextChange("header", '0')
      def oldListener = esListener
      oldListener.onEvent(mockEventSource, '5', 'ack', 'data')
    when: "the context changes"
      client.contextChange("header2", '0')
    and: "a late event arrives on the cancelled connection"
      oldListener.onEvent(mockEventSource, '6', 'ack', 'data')
    and: "we reconnect"
      client.reconnect()
    then: "no last event id is sent"
      request.header("x-featurehub") == "header2"
      request.header("Last-Event-ID") == null
  }

  def "the okhttp client uses the retryer's timeouts rather than the okhttp 10 second default"() {
    when: "we build the okhttp client"
      def okClient = new SSEClient(repository, config, retry).buildOkHttpClientBuilder(retry).build()
    then:
      1 * retry.serverConnectTimeoutMs >> 5000
      1 * retry.serverReadTimeoutMs >> 180000
      okClient.connectTimeoutMillis() == 5000
      okClient.readTimeoutMillis() == 180000
  }

  def "a connection replaced by a context change is ignored, so it cannot update features or reconnect"() {
    given: "we are connected with one context"
      config.serverEvaluation >> true
      client.contextChange("header", '0')
      def oldListener = esListener
    and: "the context changes, replacing the connection"
      client.contextChange("header2", '0')
    when: "the old connection delivers a late event, then fails because it was cancelled, then closes"
      oldListener.onEvent(mockEventSource, '6', 'features', 'old-data')
      oldListener.onFailure(mockEventSource, new IOException("Canceled"), null)
      oldListener.onClosed(mockEventSource)
    then: "none of it is acted on"
      0 * retry.fromValue(_)
      0 * retry.convertSSEState(*_)
      0 * retry.edgeResult(*_)
      0 * repository.notify(*_)
    when: "the new connection delivers an event"
      esListener.onEvent(mockEventSource, '7', 'features', 'new-data')
    then: "it is processed"
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      1 * retry.convertSSEState(SSEResultState.FEATURES, 'new-data', repository, _)
  }

  def "a context change is not blocked by an event being processed, so listeners cannot deadlock it"() {
    given: "we are connected with one context"
      config.serverEvaluation >> true
      client.contextChange("header", '0')
      def contextChanger = null
      def finishedWhileProcessing = false
    when: "a context change happens on another thread while an event is being applied"
      esListener.onEvent(mockEventSource, '5', 'features', 'data')
    then: "the context change completes without waiting for the event"
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      1 * retry.convertSSEState(SSEResultState.FEATURES, 'data', repository, _) >> {
        contextChanger = Thread.start { client.contextChange("header2", '0') }
        contextChanger.join(5000)
        finishedWhileProcessing = !contextChanger.alive
      }
      finishedWhileProcessing
      request.header("x-featurehub") == "header2"
      request.header("Last-Event-ID") == null
  }

  def "a context change while connecting cancels the old context's connection"() {
    given: "the context changes while the first connection is being made"
      config.serverEvaluation >> true
      onMakeEventSource = {
        onMakeEventSource = null
        client.contextChange("header2", '0')
      }
    when: "we connect with the first context"
      client.contextChange("header", '0')
    then: "the old context's connection is cancelled and the new context's one is used"
      1 * mockEventSource.cancel()
      connections == 2
      request.header("x-featurehub") == "header2"
  }

  def "a reconnect after a context change has already reconnected does nothing"() {
    given: "we are connected"
      config.serverEvaluation >> true
      client.contextChange("header", '0')
    and: "the connection fails, which schedules a reconnect"
      esListener.onFailure(mockEventSource, new IOException("dropped"), null)
    and: "a context change reconnects before the scheduled reconnect runs"
      client.contextChange("header2", '0')
    when: "the scheduled reconnect runs"
      client.reconnect()
    then: "no further connection is made"
      connections == 2
      request.header("x-featurehub") == "header2"
  }

  def "if we cannot even make the connection, we try again later without leaving anyone waiting"() {
    given:
      onMakeEventSource = { throw new RuntimeException("bad url") }
    when:
      def future = client.contextChange("header", '0')
    then:
      repository.getReadiness() >>> [Readiness.NotReady, Readiness.Failed]
      1 * repository.notify(SSEResultState.FAILURE)
      1 * retry.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, client)
      future.get(5, java.util.concurrent.TimeUnit.SECONDS) == Readiness.Failed
    when: "the retry connects and features arrive"
      onMakeEventSource = null
      client.reconnect()
      esListener.onEvent(mockEventSource, '1', 'features', 'data')
    then:
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      connections == 2
  }

  def "when the connection is lost, anyone waiting is released rather than waiting for the reconnect"() {
    given: "a caller waiting for features"
      def future = client.contextChange("header", '0')
    when: "the connection is lost before any arrive"
      lose(esListener)
    then:
      future.done
    where:
      lose << [
        { EventSourceListener l -> l.onFailure(null, new IOException("dropped"), null) },
        { EventSourceListener l -> l.onClosed(null) }
      ]
  }

  def "when the server rejects us, we stop retrying and release anyone waiting"() {
    given: "a caller waiting for features"
      def future = client.contextChange("header", '0')
      def response = new okhttp3.Response.Builder()
        .request(new Request.Builder().url("http://localhost").build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(status).message("rejected").build()
    when:
      esListener.onFailure(null, null, response)
    then:
      1 * retry.edgeResult(state, client)
      0 * retry.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
      future.done
    where:
      status | state
      401    | EdgeConnectionState.FAILURE
      403    | EdgeConnectionState.FAILURE
      404    | EdgeConnectionState.API_KEY_NOT_FOUND
      400    | EdgeConnectionState.API_KEY_NOT_FOUND
  }

  def "a context change waits for features from a connection that hasn't sent them yet"() {
    given: "we have connected, but no features have arrived"
      client.poll()
    when: "a context is built"
      def future = client.contextChange(null, '0')
    then:
      !future.done
    when: "the features arrive"
      esListener.onEvent(mockEventSource, '1', 'features', 'data')
    then:
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      future.done
    when: "another context is built"
      def later = client.contextChange(null, '0')
    then: "it doesn't need to wait"
      later.done
      connections == 1
  }

  def "if the wrong version of OKHttp stops us connecting, we don't retry and nobody is left waiting"() {
    given:
      onMakeEventSource = { throw new NoSuchFieldError("wrong okhttp") }
    when:
      def future = client.contextChange("header", '0')
    then:
      0 * retry.edgeResult(*_)
      future.done
  }

  def "an event we fail to apply does not move the last event id on, so the server sends it again"() {
    given:
      client.poll()
      esListener.onEvent(mockEventSource, '5', 'ack', 'data')
    when: "an event fails to apply, then the server disconnects and we reconnect"
      esListener.onEvent(mockEventSource, '6', 'features', 'bad-data')
      esListener.onClosed(mockEventSource)
      client.reconnect()
    then:
      retry.fromValue('features') >> SSEResultState.FEATURES
      1 * retry.convertSSEState(SSEResultState.FEATURES, 'bad-data', repository, _) >> { throw new RuntimeException("JSON failed") }
      request.header("Last-Event-ID") == "5"
  }

  def "once closed, a context change or poll does not connect again and completes immediately"() {
    given:
      config.serverEvaluation >> true
      client.contextChange("header", '0')
      client.close()
    when:
      def future = client.contextChange("header2", '0')
      client.poll()
      client.reconnect()
    then:
      future.done
      connections == 1
  }

  def "features from a connection replaced while they were being applied don't complete the new context's wait"() {
    given: "we are connected with one context"
      config.serverEvaluation >> true
      client.contextChange("header", '0')
      def newContext = null
    when: "the context changes while the old connection's features are being applied"
      esListener.onEvent(mockEventSource, '1', 'features', 'data')
    then: "the new context is still waiting for its own features"
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      1 * retry.convertSSEState(SSEResultState.FEATURES, 'data', repository, _) >> {
        newContext = client.contextChange("header2", '0')
      }
      newContext != null
      !newContext.done
  }

  def "once the retryer has stopped, a context change or poll does not connect and completes immediately"() {
    given:
      retry.isStopped() >> true
      config.serverEvaluation >> true
    when:
      def future = client.contextChange("header", '0')
      client.poll()
    then:
      future.done
      connections == 0
  }

  def "closing completes anyone waiting for features"() {
    given:
      def future = client.contextChange("header", '0')
    when:
      client.close()
    then:
      future.done
  }

  def "callbacks after the client is closed are ignored"() {
    given:
      client.poll()
    when:
      client.close()
      esListener.onFailure(mockEventSource, new IOException("Canceled"), null)
    then:
      1 * retry.close()
      0 * retry.edgeResult(*_)
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
}
