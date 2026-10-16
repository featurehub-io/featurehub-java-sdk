package io.featurehub.client.jersey

import com.fasterxml.jackson.databind.ObjectMapper
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.Readiness
import io.featurehub.client.edge.EdgeRetryService
import io.featurehub.client.edge.EdgeRetryer
import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.Response
import org.glassfish.jersey.media.sse.EventInput
import org.glassfish.jersey.media.sse.EventOutput
import org.glassfish.jersey.media.sse.OutboundEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class JerseySSEClientSpec extends Specification {
  private static final Logger log = LoggerFactory.getLogger(JerseySSEClientSpec.class)
  Closure<EventOutput> sseClosure
  FeatureHubConfig config
  SSETestHarness harness
  EventOutput output
  JerseySSEClient edge
  ObjectMapper mapper

  def setup() {
    mapper = new ObjectMapper()
    System.setProperty("jersey.config.test.container.port", (10000 + new Random().nextInt(1000)).toString())
    harness = new SSETestHarness()
    harness.setUp()
    def actualApiKey = "${UUID.randomUUID().toString()}/345*675".toString()
    config = harness.getConfig([actualApiKey], { String envId, String apiKey, List<String> featureHubAttrs, String extraConfig, String browserHubAttrs, String etag ->
      output = new EventOutput()
      return output
    })

    edge = new JerseySSEClient(null, config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().build()) {
      @Override
      void reconnect() {
        close();
      }
    }

    config.setEdgeService { -> edge }
  }


  def cleanup() {
    harness?.tearDown()
  }

  def "A basic client connect works as expected"() {
    given:
      edge.setNotify { EventInput i ->
        output.write(new OutboundEvent.Builder().name("ack").id("1").data("hello").build())

        edge.setNotify { EventInput i1 ->
          output.write(new OutboundEvent.Builder().name("failure").id("2").data("{}").build())

          edge.setNotify {EventInput i2 ->
            output.close()
          }
        }
      }
    when:
      def future = config.newContext().build()
    then:
      future.get().repository.readiness == Readiness.Failed
  }

  def "a basic drop of all events goes to readiness"() {
    given:
      edge.setNotify { EventInput i ->
        output.write(new OutboundEvent.Builder().name("features").id("1").data(mapper.writeValueAsString([
          new FeatureState().id(UUID.randomUUID()).key("key").l(true).value(true).type(FeatureValueType.BOOLEAN).version(1)])).build())

        edge.setNotify { EventInput i1 ->
          output.write(new OutboundEvent.Builder().name("bye").id("2").data("{}").build())

          edge.setNotify {EventInput i2 ->
            output.close()
          }
        }
      }
    when:
      def future = config.newContext().build()
    then:
      future.get().repository.readiness == Readiness.Ready
      config.repository.allFeatures.size() == 1
  }

  def "a config with a stop will prevent further calls"() {
    given:
      edge.setNotify { EventInput i ->
        output.write(new OutboundEvent.Builder().name("config").id("1").data("{\"edge.stale\": true}").build())
//        edge.setNotify { EventInput i1 ->
//          output.write(new OutboundEvent.Builder().name("bye").id("2").data("{}").build())

          edge.setNotify {EventInput i2 ->
            output.close()
          }
//        }
      }
    when:
      def future = config.newContext().build()
    then:
      future.get().repository.readiness == Readiness.Failed
      edge.stopped
  }

  def "the last event id is preserved across reconnects and cleared when the server evaluated context changes"() {
    given: "we have a server evaluated config that records the Last-Event-ID header on each connection"
      def etags = []
      def headers = []
      def serverEvalConfig = harness.getConfig(["${UUID.randomUUID()}/345675".toString()], { String envId, String apiKey, List<String> featureHubAttrs, String extraConfig, String browserHubAttrs, String etag ->
        etags.add(etag)
        headers.add(featureHubAttrs?.join(","))
        output = new EventOutput()
        return output
      })
      def serverEdge = new JerseySSEClient(null, serverEvalConfig,
        EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().withServerDisconnectRetryMs(1).withSseReadTimeoutTimeMs(500).build())
    and: "the server sends a feature event with an id and then disconnects"
      serverEdge.setNotify { EventInput i ->
        output.write(new OutboundEvent.Builder().name("features").id("7").data(mapper.writeValueAsString([
          new FeatureState().id(UUID.randomUUID()).key("key").l(true).value(true).type(FeatureValueType.BOOLEAN).version(1)])).build())

        serverEdge.setNotify { EventInput i1 ->
          serverEdge.setNotify(null)
          output.close()
        }
      }
    when: "we connect with one context"
      serverEdge.contextChange("userkey=fred", "1").get()
    then: "the reconnect sends back the last event id we received"
      new PollingConditions(timeout: 10).eventually { assert etags.size() == 2 }
      etags[0] == null
      etags[1] == "7"
      serverEdge.lastEventId == "7"
    when: "the context changes"
      serverEdge.contextChange("userkey=mary", "2")
    then: "the last event id has been cleared"
      serverEdge.lastEventId == null
    and: "we reconnect with the new context and no last event id"
      new PollingConditions(timeout: 10).eventually { assert headers.contains("userkey=mary") }
      etags[headers.indexOf("userkey=mary")] == null
      !serverEdge.stopped
    cleanup:
      serverEdge.close()
  }

  def "closing a client that is not connected still stops the retryer so a pending reconnect cannot connect"() {
    given:
      def retryer = Mock(EdgeRetryService)
      def client = new JerseySSEClient(null, config, retryer)
    when:
      client.close()
    then:
      1 * retryer.close()
  }

  def "when the server says bye and closes the connection the client reconnects"() {
    given: "a server that counts connections"
      def connections = 0
      def byeConfig = harness.getConfig(["${UUID.randomUUID()}/345*675".toString()], { String envId, String apiKey, List<String> featureHubAttrs, String extraConfig, String browserHubAttrs, String etag ->
        connections++
        output = new EventOutput()
        return output
      })
    and: "a client that reconnects quickly after a bye, but very slowly after an unexpected disconnect"
      def byeEdge = new JerseySSEClient(null, byeConfig,
        EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().withServerByeReconnectMs(1).withServerDisconnectRetryMs(60000).build())
    and: "the server sends features, says bye and closes the connection"
      byeEdge.setNotify { EventInput i ->
        output.write(new OutboundEvent.Builder().name("features").id("1").data(mapper.writeValueAsString([
          new FeatureState().id(UUID.randomUUID()).key("key").l(true).value(true).type(FeatureValueType.BOOLEAN).version(1)])).build())

        byeEdge.setNotify { EventInput i1 ->
          output.write(new OutboundEvent.Builder().name("bye").id("2").data("{}").build())

          byeEdge.setNotify { EventInput i2 ->
            byeEdge.setNotify(null)
            output.close()
          }
        }
      }
    when: "we connect"
      byeEdge.poll().get()
    then: "we reconnect using the bye timing"
      new PollingConditions(timeout: 10).eventually { assert connections == 2 }
    cleanup: "close from the server first so the client's pending read returns straight away"
      output.close()
      byeEdge.close()
  }

  def "a context change while connecting discards the old context's connection"() {
    given: "a server evaluated config that records the context of each connection"
      def headers = []
      def etags = []
      def outputs = [:]
      def raceConfig = harness.getConfig(["${UUID.randomUUID()}/345675".toString()], { String envId, String apiKey, List<String> featureHubAttrs, String extraConfig, String browserHubAttrs, String etag ->
        def header = featureHubAttrs?.join(",")
        headers.add(header)
        etags.add(etag)
        def out = new EventOutput()
        outputs[header] = out
        return out
      })
    and: "a client where the context changes just after the first connection is made"
      JerseySSEClient raceEdge
      def changed = false
      raceEdge = new JerseySSEClient(null, raceConfig,
        EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().withServerDisconnectRetryMs(60000).build()) {
        @Override
        protected EventInput makeEventSource(String header, String lastEventId) {
          def source = super.makeEventSource(header, lastEventId)
          if (!changed) {
            changed = true
            raceEdge.contextChange("userkey=mary", "2")
          }
          return source
        }
      }
    and: "only the new context's connection sends features"
      raceEdge.setNotify { EventInput i ->
        raceEdge.setNotify(null)
        outputs["userkey=mary"].write(new OutboundEvent.Builder().name("features").id("3").data(mapper.writeValueAsString([
          new FeatureState().id(UUID.randomUUID()).key("key").l(true).value(true).type(FeatureValueType.BOOLEAN).version(1)])).build())
      }
    when: "we connect with the first context"
      raceEdge.contextChange("userkey=fred", "1")
    then: "we end up connected to the new context only"
      new PollingConditions(timeout: 10).eventually { assert raceConfig.repository.readiness == Readiness.Ready }
      headers == ["userkey=fred", "userkey=mary"]
      etags == [null, null]
    cleanup: "close from the server first so the client's pending read returns straight away"
      outputs["userkey=mary"]?.close()
      raceEdge.close()
  }

  def "closing the client while it is waiting for data does not block"() {
    given: "a client that will not reconnect while we are testing"
      def idleEdge = new JerseySSEClient(null, config,
        EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().withServerDisconnectRetryMs(60000).withSseReadTimeoutTimeMs(5000).build())
    and: "the server sends features and then nothing more"
      idleEdge.setNotify { EventInput i ->
        idleEdge.setNotify(null)
        output.write(new OutboundEvent.Builder().name("features").id("1").data(mapper.writeValueAsString([
          new FeatureState().id(UUID.randomUUID()).key("key").l(true).value(true).type(FeatureValueType.BOOLEAN).version(1)])).build())
      }
      idleEdge.poll().get()
    when: "we close the client while it is blocked reading"
      Thread.sleep(200)
      def start = System.currentTimeMillis()
      idleEdge.close()
      def elapsed = System.currentTimeMillis() - start
    then: "close returns straight away rather than waiting for the read to time out"
      elapsed < 2000
    cleanup:
      output?.close()
  }

  def "a readiness callback can change context without deadlocking the connection"() {
    given: "a server evaluated config"
      def outputs = [:]
      def callbackConfig = harness.getConfig(["${UUID.randomUUID()}/345675".toString()], { String envId, String apiKey, List<String> featureHubAttrs, String extraConfig, String browserHubAttrs, String etag ->
        def out = new EventOutput()
        outputs[featureHubAttrs?.join(",")] = out
        return out
      })
      def callbackEdge = new JerseySSEClient(null, callbackConfig, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().build())
    and: "the server sends features"
      callbackEdge.setNotify { EventInput i ->
        callbackEdge.setNotify(null)
        outputs["userkey=fred"].write(new OutboundEvent.Builder().name("features").id("1").data(mapper.writeValueAsString([
          new FeatureState().id(UUID.randomUUID()).key("key").l(true).value(true).type(FeatureValueType.BOOLEAN).version(1)])).build())
      }
    when: "the readiness callback (run on the connection thread) waits for another thread to change context"
      def finished = new java.util.concurrent.CompletableFuture<Boolean>()
      callbackEdge.contextChange("userkey=fred", "1").thenRun {
        def changer = Thread.start { callbackEdge.contextChange("userkey=mary", "2") }
        changer.join(5000)
        finished.complete(!changer.alive)
      }
    then: "the context change was not blocked by the connection"
      finished.get(10, java.util.concurrent.TimeUnit.SECONDS)
    cleanup:
      outputs.values()*.close()
      callbackEdge.close()
  }

  // a server evaluated client using a retryer whose executor just records what it is asked to run, so the test decides
  // when (and on which thread) connections are made
  private JerseySSEClient recordingClient(List<Runnable> submitted, Map<String, EventInput> inputs, List<String> headers,
                                          EdgeRetryService retryer, List<String> lastEventIds = []) {
    def executor = Mock(java.util.concurrent.ExecutorService)
    executor.submit(_ as Runnable) >> { Runnable r -> submitted.add(r); null }
    retryer.getExecutorService() >> executor
    def serverEvalConfig = harness.getConfig(["${UUID.randomUUID()}/345675".toString()], { String envId, String apiKey, List<String> featureHubAttrs, String extraConfig, String browserHubAttrs, String etag ->
      new EventOutput()
    })

    return new JerseySSEClient(null, serverEvalConfig, retryer) {
      @Override
      protected EventInput makeEventSource(String header, String lastEventId) {
        headers.add(header)
        lastEventIds.add(lastEventId)
        return inputs[header]
      }
    }
  }

  def "repeated polls, reconnects and context changes before we connect only ask for one connection"() {
    given:
      def submitted = []
      def client = recordingClient(submitted, [:], [], Mock(EdgeRetryService))
    when:
      client.poll()
      client.poll()
      client.reconnect()
      client.contextChange("userkey=fred", "1")
      client.contextChange("userkey=mary", "2")
    then:
      submitted.size() == 1
  }

  def "a connection replaced by a context change stops as soon as its read returns, so it does not hold up the new one"() {
    given: "the old context's connection gets replaced while it is reading, and then its read returns nothing"
      def submitted = []
      def headers = []
      def reads = 0
      JerseySSEClient client
      def fredInput = Mock(EventInput)
      fredInput.isClosed() >> { reads > 1 } // only stops a client that keeps reading
      fredInput.read() >> {
        reads++
        if (reads == 1) {
          client.contextChange("userkey=mary", "2")
        }
        null
      }
      def stopped = false
      def maryInput = Mock(EventInput)
      maryInput.read() >> { stopped = true; null }
      def retryer = Mock(EdgeRetryService)
      retryer.isStopped() >> { stopped }
      client = recordingClient(submitted, ["userkey=fred": fredInput, "userkey=mary": maryInput], headers, retryer)
    when: "we connect with the first context"
      client.contextChange("userkey=fred", "1")
      submitted[0].run()
    then: "the old connection read only once and the context change asked for a new one"
      reads == 1
      submitted.size() == 2
    when: "the new connection runs"
      submitted[1].run()
    then:
      headers == ["userkey=fred", "userkey=mary"]
  }

  def "an event we fail to apply does not move the last event id on, so the server sends it again"() {
    given: "a connection that sends an event that applies and then one that fails"
      def submitted = []
      def events = [
        Mock(org.glassfish.jersey.media.sse.InboundEvent) { getName() >> "ack"; getId() >> "5"; readData() >> "data" },
        Mock(org.glassfish.jersey.media.sse.InboundEvent) { getName() >> "features"; getId() >> "6"; readData() >> "bad-data" }
      ]
      def stopped = false
      def input = Mock(EventInput)
      input.read() >> {
        if (events) {
          return events.remove(0)
        }
        stopped = true // ends the connection
        null
      }
      def retryer = Mock(EdgeRetryService)
      retryer.isStopped() >> { stopped }
      retryer.fromValue("ack") >> io.featurehub.sse.model.SSEResultState.ACK
      retryer.fromValue("features") >> io.featurehub.sse.model.SSEResultState.FEATURES
      retryer.convertSSEState(io.featurehub.sse.model.SSEResultState.FEATURES, "bad-data", _, _) >> { throw new RuntimeException("JSON failed") }
      def lastEventIds = []
      def client = recordingClient(submitted, ["userkey=fred": input], [], retryer, lastEventIds)
    when: "we connect, receive both events and then reconnect"
      client.contextChange("userkey=fred", "1")
      submitted[0].run()
      stopped = false
      client.reconnect()
      submitted[1].run()
    then: "the reconnect asks for everything after the event that applied"
      lastEventIds == [null, "5"]
  }

  def "features from a connection replaced while they were being applied don't complete the new context's wait"() {
    given: "a connection that sends features, during which the context changes"
      def submitted = []
      def events = [
        Mock(org.glassfish.jersey.media.sse.InboundEvent) { getName() >> "features"; getId() >> "1"; readData() >> "data" }
      ]
      def input = Mock(EventInput)
      input.read() >> { events ? events.remove(0) : null }
      def retryer = Mock(EdgeRetryService)
      retryer.fromValue("features") >> io.featurehub.sse.model.SSEResultState.FEATURES
      JerseySSEClient client
      def newContext = null
      retryer.convertSSEState(io.featurehub.sse.model.SSEResultState.FEATURES, "data", _, _) >> {
        newContext = client.contextChange("userkey=mary", "2")
      }
      client = recordingClient(submitted, ["userkey=fred": input], [], retryer)
    when:
      client.contextChange("userkey=fred", "1")
      submitted[0].run()
    then: "the new context is still waiting for its own features"
      newContext != null
      !newContext.done
  }

  def "once the retryer has stopped, a context change or poll does not connect and completes immediately"() {
    given:
      def submitted = []
      def retryer = Mock(EdgeRetryService)
      retryer.isStopped() >> true
      def client = recordingClient(submitted, [:], [], retryer)
    when:
      def future = client.contextChange("userkey=fred", "1")
      def polled = client.poll()
    then:
      future.done
      polled.done
      submitted.isEmpty()
  }

  // a client whose connection attempts all fail with the given exception
  private JerseySSEClient failingClient(List<Runnable> submitted, EdgeRetryService retryer, Exception failure) {
    def executor = Mock(java.util.concurrent.ExecutorService)
    executor.submit(_ as Runnable) >> { Runnable r -> submitted.add(r); null }
    retryer.getExecutorService() >> executor
    def serverEvalConfig = harness.getConfig(["${UUID.randomUUID()}/345675".toString()], { String envId, String apiKey, List<String> featureHubAttrs, String extraConfig, String browserHubAttrs, String etag ->
      new EventOutput()
    })

    return new JerseySSEClient(null, serverEvalConfig, retryer) {
      @Override
      protected EventInput makeEventSource(String header, String lastEventId) {
        throw failure
      }
    }
  }

  def "if the server rejects us, anyone waiting is told we have failed"() {
    given: "a server that rejects us"
      def submitted = []
      def retryer = Mock(EdgeRetryService)
      def client = failingClient(submitted, retryer, new WebApplicationException(Response.status(status).build()))
    when:
      def future = client.contextChange("userkey=fred", "1")
      submitted[0].run()
    then:
      1 * retryer.edgeResult(state, client)
      future.done
      future.get() == Readiness.Failed
    where:
      status | state
      401    | io.featurehub.client.edge.EdgeConnectionState.FAILURE
      403    | io.featurehub.client.edge.EdgeConnectionState.FAILURE
      404    | io.featurehub.client.edge.EdgeConnectionState.API_KEY_NOT_FOUND
  }

  def "if we can't connect, anyone waiting is released rather than waiting for the retry"() {
    given:
      def submitted = []
      def retryer = Mock(EdgeRetryService)
      def client = failingClient(submitted, retryer, new java.net.ConnectException("refused"))
    when:
      def future = client.contextChange("userkey=fred", "1")
      submitted[0].run()
    then:
      1 * retryer.edgeResult(io.featurehub.client.edge.EdgeConnectionState.CONNECTION_FAILURE, client)
      future.done
  }

  def "if the server closes the connection before sending features, anyone waiting is released"() {
    given: "a connection the server closes straight away"
      def submitted = []
      def closed = false
      def input = Mock(EventInput)
      input.isClosed() >> { closed }
      input.read() >> { closed = true; null }
      def retryer = Mock(EdgeRetryService)
      def client = recordingClient(submitted, ["userkey=fred": input], [], retryer)
    when:
      def future = client.contextChange("userkey=fred", "1")
      submitted[0].run()
    then:
      1 * retryer.edgeResult(io.featurehub.client.edge.EdgeConnectionState.SERVER_WAS_DISCONNECTED, client)
      future.done
  }

  def "a poll waits for features from a connection that hasn't sent them yet"() {
    given: "a connection that sends features after we poll"
      def submitted = []
      JerseySSEClient client
      def polled = null
      def doneBeforeFeatures = null
      def doneAfterFeatures = null
      def reads = 0
      def stopped = false
      def input = Mock(EventInput)
      input.read() >> {
        reads++
        if (reads == 1) {
          polled = client.poll()
          doneBeforeFeatures = polled.done
          return Mock(org.glassfish.jersey.media.sse.InboundEvent) { getName() >> "features"; getId() >> "1"; readData() >> "data" }
        }
        doneAfterFeatures = polled.done
        stopped = true
        null
      }
      def retryer = Mock(EdgeRetryService)
      retryer.isStopped() >> { stopped }
      retryer.fromValue("features") >> io.featurehub.sse.model.SSEResultState.FEATURES
      client = recordingClient(submitted, ["userkey=fred": input], [], retryer)
    when:
      client.contextChange("userkey=fred", "1")
      submitted[0].run()
    then:
      doneBeforeFeatures == false
      doneAfterFeatures == true
      submitted.size() == 1
  }

  def "those who start waiting on the next attempt are not released when a connection attempt fails"() {
    given: "a connection attempt that fails, and while the failure is being handled another caller starts waiting"
      def submitted = []
      def retryer = Mock(EdgeRetryService)
      JerseySSEClient client
      def later = null
      retryer.edgeResult(io.featurehub.client.edge.EdgeConnectionState.CONNECTION_FAILURE, _) >> { later = client.poll() }
      client = failingClient(submitted, retryer, new java.net.ConnectException("refused"))
    when:
      def first = client.poll()
      submitted[0].run()
    then: "the first caller is released, but the later one waits for the next attempt"
      first.done
      later != null
      !later.done
      submitted.size() == 2
  }

  def "an event we fail to apply doesn't leave anyone waiting"() {
    given: "a connection that sends an event that fails to apply"
      def submitted = []
      def stopped = false
      def doneAfterFailure = null
      JerseySSEClient client
      def future = null
      def events = [
        Mock(org.glassfish.jersey.media.sse.InboundEvent) { getName() >> "features"; getId() >> "1"; readData() >> "bad-data" }
      ]
      def input = Mock(EventInput)
      input.read() >> {
        if (events) {
          return events.remove(0)
        }
        doneAfterFailure = future.done
        stopped = true
        null
      }
      def retryer = Mock(EdgeRetryService)
      retryer.isStopped() >> { stopped }
      retryer.fromValue("features") >> io.featurehub.sse.model.SSEResultState.FEATURES
      retryer.convertSSEState(*_) >> { throw new RuntimeException("JSON failed") }
      client = recordingClient(submitted, ["userkey=fred": input], [], retryer)
    when:
      future = client.contextChange("userkey=fred", "1")
      submitted[0].run()
    then: "the caller was released straight after the failure, while the connection was still open"
      doneAfterFailure == true
  }

  def "closing the client also closes its JAX-RS client"() {
    given:
      def sse = new JerseySSEClient(null, config, Mock(EdgeRetryService))
    when:
      sse.close()
    then:
      new PollingConditions(timeout: 5).eventually { assert sse.client.isClosed() }
  }

  def "closing several connections does not make them wait for each other"() {
    given: "connections whose close blocks, as it does while another thread is reading them"
      def started = new java.util.concurrent.CountDownLatch(3)
      def release = new java.util.concurrent.CountDownLatch(1)
      def inputs = (1..3).collect {
        def input = Mock(EventInput)
        input.close() >> { started.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        input
      }
    when:
      inputs.each { JerseySSEClient.closeInBackground(it) }
    then: "all of them start closing at once"
      started.await(5, java.util.concurrent.TimeUnit.SECONDS)
    cleanup:
      release.countDown()
  }

//  def "basic initialization test works as expect"() {
//    given: "i have a valid url"
//      def url = new EdgeFeatureHubConfig("http://localhost:80/", "sdk-url")
//    when: "i initialize with a valid kind of sdk url"
//      def client = new JerseySSEClient(null, url, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build()) {
//        @Override
//        protected WebTarget makeEventSourceTarget(Client client, String sdkUrl) {
//          targetUrl = sdkUrl
//          return super.makeEventSourceTarget(client, sdkUrl)
//        }
//      }
//    then: "the urls are correctly initialize"
//      targetUrl == url.realtimeUrl
//      basePath == 'http://localhost:80'
//      sdkPartialUrl.apiKey() == 'sdk-url'
//  }
//
//  def "test the set feature sdk call"() {
//    given: "I have a mock feature service"
//      def config = new EdgeFeatureHubConfig("http://localhost:80/", "sdk-url")
//      def testApi = new TestSDKClient(config)
//    and: "i have a feature state update"
//      def update = new FeatureStateUpdate().lock(true)
//    when: "I call to set a feature"
//      testApi.setFeatureState(config.apiKey(), "key", update)
//    then:
//      mockFeatureService != null
//      1 * mockFeatureService.setFeatureState("sdk-url", "key", update)
//  }
//
//  def "test the set feature sdk call using a Feature"() {
//    given: "I have a mock feature service"
//      mockFeatureService = Mock(FeatureService)
//    and: "I have a client and mock the feature service url"
//      def client = new JerseyClient(new EdgeFeatureHubConfig("http://localhost:80/", "sdk-url2"),
//        false, new ClientFeatureRepository(1), null) {
//        @Override
//        protected FeatureService makeFeatureServiceClient(ApiClient apiClient) {
//          return mockFeatureService
//        }
//      }
//    and: "i have a feature state update"
//      def update = new FeatureStateUpdate().lock(true)
//    when: "I call to set a feature"
//      client.setFeatureState(InternalFeature.FEATURE, update)
//    then:
//      mockFeatureService != null
//      1 * mockFeatureService.setFeatureState("sdk-url2", "FEATURE", update)
//  }

//  def "a client side evaluation header does not trigger the context header to be set"() {
//    given: "i have a client with a client eval url"
//      def config = new EdgeFeatureHubConfig("http://localhost:80/", "sdk*url2")
//      def client = new JerseySSEClient(null, config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build())
//    and: "we set up a server"
//      def harness = new SSETestHarness(config)
//
//    when: "i set attributes"
//      client.contextChange("fred=mary,susan", '0').get()
//    then:
//
//  }
//
//  def "a server side evaluation header does not trigger the context header to be set if it is null"() {
//    given: "i have a client with a server eval url"
//      def client = new JerseyClient(new EdgeFeatureHubConfig("http://localhost:80/", "sdk-url2"),
//        false, new ClientFeatureRepository(1), null)
//      client.neverConnect = true  // groovy is groovy
//    when: "i set attributes"
//      client.contextChange(null, '0')
//    then:
//      client.featurehubContextHeader == null
//
//  }
//
//  def "a server side evaluation header does trigger the context header to be set"() {
//    given: "i have a client with a client eval url"
//      def client = new JerseyClient(new EdgeFeatureHubConfig("http://localhost:80/", "sdk-url2"),
//        false, new ClientFeatureRepository(1), null)
//    when: "i set attributes"
//      client.contextChange("fred=mary,susan", '0')
//    then:
//      client.featurehubContextHeader != null
//  }

}
