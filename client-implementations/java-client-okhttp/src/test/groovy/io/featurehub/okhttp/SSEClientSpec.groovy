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
  int connectionsMade
  Closure onMakeEventSource
  // if set, how long a stream must stay open to count as working
  Long workingMs

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
        connectionsMade++
        onMakeEventSource?.call()
        return mockEventSource
      }

      @Override
      protected long workingStreamMs() {
        return workingMs != null ? workingMs : super.workingStreamMs()
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
      1 * repository.getReadiness() >> Readiness.NotReady
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
      1 * repository.getReadiness() >> Readiness.NotReady
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      1 * config.getEnvironmentId() >> envId
      _ * retry.isStopped()
      0 * _
  }

  def "open then immediate failure"() {
    when: "i poll"
      client.poll()
      esListener.onOpen(mockEventSource, response(200))
      esListener.onFailure(mockEventSource, null, null)
    then:
      1 * config.getRealtimeUrl() >> "http://localhost"
      2 * config.baseUrl() >> "http://localhost" // used by trace logs
      1 * repository.getReadiness() >> Readiness.NotReady
      1 * retry.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, client)
      _ * retry.isStopped()
      0 * _
  }


  private static okhttp3.Response response(int status) {
    return new okhttp3.Response.Builder()
      .request(new Request.Builder().url("http://localhost").build())
      .protocol(okhttp3.Protocol.HTTP_1_1)
      .code(status).message("status").build()
  }

  def "a failure before the connection ever opened backs off rather than reconnecting immediately"() {
    given:
      client.poll()
    when:
      esListener.onFailure(mockEventSource, failure, status ? response(status) : null)
    then:
      1 * retry.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, client)
      0 * retry.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
    where:
      failure                                      | status
      new UnknownHostException("does-not-exist")   | 0
      null                                         | 503
      new IOException("unexpected end of stream")  | 0
      null                                         | 429 // too many requests may well go away, so don't stop
      null                                         | 408
  }

  def "a failure we will retry doesn't mark us as failed, even if we never had features"() {
    given:
      config.serverEvaluation >> true
      def future = client.contextChange("header", '0')
    when:
      esListener.onFailure(mockEventSource, new UnknownHostException("does-not-exist"), null)
    then:
      1 * retry.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, client)
      _ * repository.getReadiness() >> Readiness.NotReady
      0 * repository.notify(*_)
      future.get() == Readiness.NotReady
  }

  def "once the retryer has stopped after we lost the connection, having never had features means we failed"() {
    given:
      config.serverEvaluation >> true
      def future = client.contextChange("header", '0')
    when:
      esListener.onFailure(mockEventSource, null, response(404))
    then:
      1 * retry.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, client)
      _ * retry.isStopped() >> true
      _ * repository.getReadiness() >> Readiness.NotReady
      1 * repository.notify(SSEResultState.FAILURE)
      future.done
  }

  def "a failure event with no data is still recorded, and stops the retryer"() {
    given:
      config.serverEvaluation >> true
      def future = client.contextChange("header", '0')
    when:
      esListener.onEvent(mockEventSource, null, 'failure', null)
    then:
      1 * retry.fromValue('failure') >> SSEResultState.FAILURE
      1 * retry.convertSSEState(SSEResultState.FAILURE, null, repository, _)
      1 * retry.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, client)
      future.done
  }

  def "an event that stops the retryer cancels the connection rather than leaving it open"() {
    given:
      config.serverEvaluation >> true
      def stopped = false
      retry.isStopped() >> { stopped }
      def future = client.contextChange("header", '0')
    when:
      esListener.onEvent(mockEventSource, null, 'failure', null)
    then:
      1 * retry.fromValue('failure') >> SSEResultState.FAILURE
      1 * retry.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, client) >> { stopped = true }
      1 * mockEventSource.cancel()
      future.done
  }

  def "those who start waiting on the replacement connection are not released when the old one is lost"() {
    given: "a caller waiting on the first connection"
      config.serverEvaluation >> true
      def first = client.contextChange(null, '0')
      def later = null
    when: "it closes, and before it has finished a reconnect is made and another caller starts waiting on it"
      esListener.onClosed(mockEventSource)
    then:
      1 * retry.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, client) >> {
        client.reconnect()
        later = client.contextChange(null, '0')
      }
      first.done
      later != null
      !later.done
  }

  def "an event we fail to apply doesn't leave anyone waiting"() {
    given:
      def future = client.contextChange("header", '0')
    when:
      esListener.onEvent(mockEventSource, '1', 'features', 'bad-data')
    then:
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      1 * retry.convertSSEState(SSEResultState.FEATURES, 'bad-data', repository, _) >> { throw new RuntimeException("JSON failed") }
      future.done
  }

  def "once closed, a connection that was already starting doesn't make a new OKHttp client"() {
    given: "a client that makes real connections"
      def realClient = new SSEClient(repository, config, retry)
    when:
      realClient.close()
      def source = realClient.makeEventSource(new Request.Builder().url("http://localhost").build(), Mock(EventSourceListener))
    then:
      source == null
      realClient.client == null
  }

  def "a failure after the stream has sent us something reconnects immediately"() {
    given:
      retry.fromValue('features') >> SSEResultState.FEATURES
      client.poll()
      esListener.onOpen(mockEventSource, response(200))
      esListener.onEvent(mockEventSource, '1', 'features', 'data')
    when:
      esListener.onFailure(mockEventSource, new IOException("dropped"), null)
    then:
      1 * retry.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
  }

  def "a stream that is accepted and then dropped before sending anything backs off rather than reconnecting immediately"() {
    given:
      client.poll()
      esListener.onOpen(mockEventSource, response(200))
    when:
      lose(esListener)
    then:
      1 * retry.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, client)
      0 * retry.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
    where:
      lose << [
        { EventSourceListener l -> l.onClosed(null) },
        { EventSourceListener l -> l.onFailure(null, new IOException("connection reset"), null) }
      ]
  }

  def "a resumed stream that is quiet until our read timeout, or until the server drops it, reconnects straight away"() {
    given: "we have features, and have reconnected with the last event id the server is up to date with"
      retry.fromValue('features') >> SSEResultState.FEATURES
      client.poll()
      esListener.onEvent(mockEventSource, '5', 'features', 'data')
      esListener.onClosed(mockEventSource)
      client.reconnect()
      assert request.header("Last-Event-ID") == "5"
    and: "the new stream opens, and stays open long enough to count as working while sending nothing"
      workingMs = 0
      esListener.onOpen(mockEventSource, response(200))
    when: "a context is built while it is quiet"
      def built = client.contextChange(null, '0')
    then: "it doesn't wait, we already have the features"
      built.done
    when:
      lose(esListener)
    then:
      1 * retry.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
      0 * retry.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, client)
    where:
      lose << [
        { EventSourceListener l -> l.onFailure(null, new SocketTimeoutException("timeout"), null) },
        { EventSourceListener l -> l.onClosed(null) }
      ]
  }

  def "a quiet stream the server says bye on straight away reconnects straight away"() {
    given:
      retry.fromValue('bye') >> SSEResultState.BYE
      client.poll()
      esListener.onOpen(mockEventSource, response(200))
    when:
      esListener.onEvent(mockEventSource, null, 'bye', null)
      esListener.onClosed(mockEventSource)
    then:
      1 * retry.edgeResult(EdgeConnectionState.SERVER_SAID_BYE, client)
  }

  def "a stream that only sends an ack and is then dropped straight away backs off"() {
    given:
      retry.fromValue('ack') >> SSEResultState.ACK
      client.poll()
      esListener.onOpen(mockEventSource, response(200))
      esListener.onEvent(mockEventSource, '1', 'ack', null)
    when:
      lose(esListener)
    then:
      1 * retry.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, client)
      0 * retry.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
    where:
      lose << [
        { EventSourceListener l -> l.onClosed(null) },
        { EventSourceListener l -> l.onFailure(null, new IOException("connection reset"), null) }
      ]
  }

  def "we don't resume with a last event id until we have the context's features"() {
    given: "a stream that sent an ack with an id, then dropped before any features"
      retry.fromValue('ack') >> SSEResultState.ACK
      client.poll()
      esListener.onEvent(mockEventSource, '5', 'ack', null)
      esListener.onClosed(mockEventSource)
    when:
      client.reconnect()
    then: "the server sends us everything rather than thinking we are up to date"
      connectionsMade == 2
      request.header("Last-Event-ID") == null
  }

  def "only the features (or failing to apply them) release those waiting, and later callers still wait for the features"() {
    given:
      retry.fromValue('bye') >> SSEResultState.BYE
      retry.fromValue('features') >> SSEResultState.FEATURES
      retry.convertSSEState(SSEResultState.FEATURES, 'bad-data', repository, _) >> { throw new RuntimeException("JSON failed") }
      config.serverEvaluation >> true
      def first = client.contextChange(null, '0')
    when:
      deliver(esListener)
      def later = client.contextChange(null, '0')
    then: "a bye before the features would have them evaluate the previous context's values"
      first.done == released
      !later.done
    where:
      deliver << [
        { EventSourceListener l -> l.onEvent(null, null, 'bye', null) },
        { EventSourceListener l -> l.onEvent(null, '1', 'features', 'bad-data') }
      ]
      released << [false, true]
  }

  def "a client side key's context change doesn't wait for features, it only makes sure we are connecting"() {
    when: "i change context"
      def future = client.contextChange("header", '0')
    then: "it is done straight away, and the header isn't sent as client side contexts never change the connection"
      1 * config.getRealtimeUrl() >> "http://localhost"
      1 * config.isServerEvaluation() >> false
      1 * repository.getReadiness() >> Readiness.NotReady
      _ * retry.isStopped()
      0 * _
      future.get() == Readiness.NotReady
      connectionsMade == 1
      request.header("x-featurehub") == null
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
      retry.fromValue('features') >> SSEResultState.FEATURES
      client.poll()
      assert request.header("Last-Event-ID") == null
    when: "we receive an event with an id, then one without, then the server disconnects and we reconnect"
      esListener.onEvent(mockEventSource, '5', 'features', 'data')
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
      retry.serverConnectTimeoutMs >> 5000
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
      connectionsMade == 2
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
      connectionsMade == 2
      request.header("x-featurehub") == "header2"
  }

  def "if we cannot even make the connection, we try again later without leaving anyone waiting"() {
    given:
      config.serverEvaluation >> true
      onMakeEventSource = { throw new RuntimeException("bad url") }
    when:
      def future = client.contextChange("header", '0')
    then:
      _ * repository.getReadiness() >> Readiness.NotReady
      0 * repository.notify(*_)
      1 * retry.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, client)
      future.get(5, java.util.concurrent.TimeUnit.SECONDS) == Readiness.NotReady
    when: "the retry connects and features arrive"
      onMakeEventSource = null
      client.reconnect()
      esListener.onEvent(mockEventSource, '1', 'features', 'data')
    then:
      1 * retry.fromValue('features') >> SSEResultState.FEATURES
      connectionsMade == 2
  }

  def "when the connection is lost, anyone waiting is released rather than waiting for the reconnect"() {
    given: "a caller waiting for features"
      config.serverEvaluation >> true
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

  def "when the server tells us the api key doesn't exist, we stop retrying and release anyone waiting"() {
    given: "a caller waiting for features"
      config.serverEvaluation >> true
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
      401    | EdgeConnectionState.FAILURE // often a wrong url, retrying won't change it
      403    | EdgeConnectionState.FAILURE
      404    | EdgeConnectionState.API_KEY_NOT_FOUND
      400    | EdgeConnectionState.API_KEY_NOT_FOUND
  }

  def "a server evaluated context change waits for features from a connection that hasn't sent them yet"() {
    given: "we have connected, but no features have arrived"
      config.serverEvaluation >> true
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
      connectionsMade == 1
  }

  def "while the retryer is backing off, a context change or poll does not connect early"() {
    given: "we were connected, and the connection was lost"
      client.poll()
      lose(esListener)
    when: "contexts are built and we are polled before the retryer reconnects"
      def future = client.contextChange(null, '0')
      client.poll()
    then: "nothing connects, and the caller isn't held up as no features are coming yet"
      connectionsMade == 1
      future.done
    when: "the retryer reconnects"
      client.reconnect()
    then:
      connectionsMade == 2
    where:
      lose << [
        { EventSourceListener l -> l.onFailure(null, new UnknownHostException("does-not-exist"), null) },
        { EventSourceListener l -> l.onFailure(null, new IOException("dropped"), null) },
        { EventSourceListener l -> l.onClosed(null) }
      ]
  }

  def "while the retryer is backing off after we couldn't make the connection, a context change does not connect early"() {
    given:
      onMakeEventSource = { throw new RuntimeException("bad url") }
      client.poll()
      onMakeEventSource = null
    when:
      def future = client.contextChange(null, '0')
    then:
      connectionsMade == 1
      future.done
  }

  def "a server evaluated context change while the retryer is backing off connects straight away for the new context"() {
    given:
      config.serverEvaluation >> true
      client.contextChange("header", '0')
      esListener.onFailure(null, new IOException("dropped"), null)
    when:
      def future = client.contextChange("header2", '0')
    then:
      connectionsMade == 2
      request.header("x-featurehub") == "header2"
      !future.done
    when: "the scheduled reconnect runs"
      client.reconnect()
    then: "it doesn't make another connection"
      connectionsMade == 2
  }

  def "a server evaluated context change while the retryer is backing off cancels the retryer's reconnect"() {
    given: "the connection fails, so the retryer starts backing off"
      config.serverEvaluation >> true
      client.contextChange("header", '0')
      esListener.onFailure(null, new IOException("dropped"), null)
    when: "the context changes, connecting straight away"
      client.contextChange("header2", '0')
    then: "the old backoff can't cut a later one short, or hold a later reconnect up behind it"
      1 * retry.cancelReconnects()
      connectionsMade == 2
  }

  def "a context change that doesn't override a backoff doesn't cancel reconnects"() {
    given:
      config.serverEvaluation >> true
      client.contextChange("header", '0')
    when:
      client.contextChange("header2", '0')
    then:
      0 * retry.cancelReconnects()
  }

  def "once we have features, a reconnect doesn't make contexts wait for the new connection to send them again"() {
    given: "we have features"
      retry.fromValue('features') >> SSEResultState.FEATURES
      retry.fromValue('bye') >> SSEResultState.BYE
      retry.fromValue('ack') >> SSEResultState.ACK
      client.poll()
      esListener.onEvent(mockEventSource, '1', 'features', 'data')
    and: "the server says bye and we reconnect, but the new connection sends nothing new"
      esListener.onEvent(mockEventSource, '2', 'bye', null)
      esListener.onClosed(mockEventSource)
      client.reconnect()
      esListener.onEvent(mockEventSource, null, 'ack', null)
    when:
      def future = client.contextChange(null, '0')
    then:
      connectionsMade == 2
      future.done
  }

  def "if the wrong version of OKHttp stops us connecting, we don't retry, we have failed and nobody is left waiting"() {
    given:
      config.serverEvaluation >> true
      onMakeEventSource = { throw new NoSuchFieldError("wrong okhttp") }
    when:
      def future = client.contextChange("header", '0')
    then:
      0 * retry.edgeResult(*_)
      _ * repository.getReadiness() >> Readiness.NotReady
      1 * repository.notify(SSEResultState.FAILURE)
      future.done
  }

  def "an event we fail to apply does not move the last event id on, so the server sends it again"() {
    given:
      retry.fromValue('features') >> SSEResultState.FEATURES
      client.poll()
      esListener.onEvent(mockEventSource, '5', 'features', 'data')
    when: "an event fails to apply, then the server disconnects and we reconnect"
      esListener.onEvent(mockEventSource, '6', 'features', 'bad-data')
      esListener.onClosed(mockEventSource)
      client.reconnect()
    then:
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
      connectionsMade == 1
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
      connectionsMade == 0
  }

  def "closing completes anyone waiting for features"() {
    given:
      config.serverEvaluation >> true
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

  def "if okhttp rejects the request (e.g. a malformed url), we don't retry, we have failed and never connect again"() {
    given:
      config.serverEvaluation >> true
      onMakeEventSource = { throw new IllegalArgumentException("unexpected url") }
    when:
      def future = client.contextChange("header", '0')
      client.poll()
    then:
      0 * retry.edgeResult(*_)
      _ * repository.getReadiness() >> Readiness.NotReady
      1 * repository.notify(SSEResultState.FAILURE)
      future.done
      connectionsMade == 1
  }

  def "a subclass's okhttp builder that sets no timeouts still gets the retryer's, but its own are kept"() {
    given: "what a subclass's buildOkHttpClientBuilder made"
      retry.serverConnectTimeoutMs >> 5000
      retry.serverReadTimeoutMs >> 180000
      def builder = new okhttp3.OkHttpClient.Builder()
      if (ownConnect) {
        builder.connectTimeout(java.time.Duration.ofMillis(2000))
      }
    when:
      def okClient = client.withTimeouts(builder.build())
    then:
      okClient.readTimeoutMillis() == 180000
      okClient.connectTimeoutMillis() == connect
    where:
      ownConnect | connect
      false      | 5000
      true       | 2000
  }
}
