package io.featurehub.client.jersey

import com.fasterxml.jackson.databind.ObjectMapper
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.Readiness
import io.featurehub.client.edge.EdgeRetryer
import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import org.glassfish.jersey.media.sse.EventInput
import org.glassfish.jersey.media.sse.EventOutput
import org.glassfish.jersey.media.sse.OutboundEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification

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
    config = harness.getConfig(["123/345*675"], { String envId, String apiKey, List<String> featureHubAttrs, String extraConfig, String browserHubAttrs, String etag ->
      output = new EventOutput()
      return output
    })

    edge = new JerseySSEClient(null, config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build()) {
      @Override
      void reconnect() {
        close();
      }
    }

    config.setEdgeService { -> edge }
  }


  def cleanup() {
    harness.tearDown()
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
