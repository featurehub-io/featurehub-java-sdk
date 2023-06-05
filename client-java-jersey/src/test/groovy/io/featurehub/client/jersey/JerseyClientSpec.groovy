package io.featurehub.client.jersey

import cd.connect.openapi.support.ApiClient
import io.featurehub.client.ClientFeatureRepository
import io.featurehub.client.EdgeFeatureHubConfig
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.Readiness
import io.featurehub.client.edge.EdgeRetryer
import io.featurehub.sse.api.FeatureService
import io.featurehub.sse.model.FeatureStateUpdate
import org.glassfish.jersey.media.sse.EventOutput
import org.glassfish.jersey.media.sse.EventSource
import org.glassfish.jersey.media.sse.OutboundEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification

import javax.ws.rs.client.Client
import javax.ws.rs.client.WebTarget
import javax.ws.rs.sse.OutboundSseEvent

class JerseyClientSpec extends Specification {
  private static final Logger log = LoggerFactory.getLogger(JerseyClientSpec.class)
  Closure<EventOutput> sseClosure
  FeatureHubConfig config
  SSETestHarness harness

  def setup() {
    harness = new SSETestHarness()
    harness.setUp()
  }

  def teardown() {
    harness.tearDown()
  }

  def "A basic client connect works as expected"() {
    given:
      EventOutput output
      config = harness.getConfig(["123/345*675"]) { envId, apiKey, featureHubAttrs, extraConfig, browserHubAttrs, etag ->
        output = new EventOutput()
        return output
      }
    when:
      def future = config.newContext().build()
    and:
      output.write(new OutboundEvent.Builder().name("ack").id("1").build())
      output.write(new OutboundEvent.Builder().name("failure").id("2").build())
      output.close()
    then:
      future.get().repository.readiness == Readiness.Failed
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
