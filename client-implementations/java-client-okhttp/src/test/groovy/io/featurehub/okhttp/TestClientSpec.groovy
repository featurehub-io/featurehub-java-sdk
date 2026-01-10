package io.featurehub.okhttp

import com.fasterxml.jackson.databind.ObjectMapper
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.javascript.Jackson2ObjectMapper
import io.featurehub.javascript.JavascriptObjectMapper
import io.featurehub.sse.model.FeatureStateUpdate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import spock.lang.Specification

import java.util.concurrent.TimeUnit

class TestClientSpec extends Specification {
  MockWebServer mockWebServer
  FeatureHubConfig config
  InternalFeatureRepository repo
  JavascriptObjectMapper mapper
  TestClient client

  def setup() {
    mapper = new Jackson2ObjectMapper()
    config = Mock()
    repo = Mock()
    repo.getJsonObjectMapper() >> mapper
    config.repository >> repo
    mockWebServer = new MockWebServer()

    def url = mockWebServer.url("/features").toString()
    config.baseUrl() >> url.substring(0, url.length())
    config.apiKey() >> "one"
    config.serverEvaluation >> true
    config.internalRepository >> repo
    client = new TestClient(config)
  }

  def cleanup() {
    client.close()
    mockWebServer.shutdown()
  }

  def "i make a call and it returns the correct value"() {
    given:
      def update = new FeatureStateUpdate().value(20).lock(false).updateValue(true)
      client.setFeatureState('key', update)
      def updateAsString = mapper.featureStateUpdateToString(update)
    when:
      def req = mockWebServer.takeRequest(100, TimeUnit.MILLISECONDS)
      mockWebServer.enqueue(new MockResponse().setResponseCode(200))
    then:
      req.path == "/features/one/key"
      req.headers.get('content-type').contains('application/json')
      req.body.readUtf8() == updateAsString
  }
}
