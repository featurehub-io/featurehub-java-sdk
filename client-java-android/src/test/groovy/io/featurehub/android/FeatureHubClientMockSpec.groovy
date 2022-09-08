package io.featurehub.android

import com.fasterxml.jackson.databind.ObjectMapper
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.FeatureStore
import io.featurehub.sse.model.FeatureEnvironmentCollection
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import spock.lang.Specification

class FeatureHubClientMockSpec extends Specification {
  MockWebServer mockWebServer
  FeatureHubClient client
  FeatureHubConfig config
  FeatureStore store
  ObjectMapper mapper

  def setup() {
    mapper = new ObjectMapper()
    config = Mock()
    store = Mock()
    mockWebServer = new MockWebServer()

    def url = mockWebServer.url("/").toString()
    client = new FeatureHubClient(url.substring(0, url.length()-1), ["one", "two"], store, config, 0)
    mockWebServer.url("/features")
  }

  def cleanup() {
    client.close()
    mockWebServer.shutdown()
  }

  def poll() {
    client.poll()
    sleep(500)
  }

  def "a request for a known feature set with zero features"() {
    given: "a response"
      mockWebServer.enqueue(new MockResponse().with {
        setBody(mapper.writeValueAsString([new FeatureEnvironmentCollection().id(UUID.randomUUID()).features([])]))
        setResponseCode(200)
      })
    when:
      poll()
    then:
      1 * store.notify([])
  }

  def "a request with an etag and a cache-control should work as expected"() {
    given: "a etag and cache control response"
      mockWebServer.enqueue( new MockResponse().with {
        setBody(mapper.writeValueAsString([new FeatureEnvironmentCollection().id(UUID.randomUUID()).features([])]))
        setHeader("etag", "etag12345")
        setResponseCode(200)
      })
    and: "a new response with a 236"
      mockWebServer.enqueue( new MockResponse().with {
        setBody(mapper.writeValueAsString([new FeatureEnvironmentCollection().id(UUID.randomUUID()).features([])]))
        setHeader("etag", "etag12345")
        setHeader("cache-control", "bork, bork, max-age=20, sister")
        setResponseCode(236)
      })
    when:
      poll()
      def etag = client.etag
      def req1 = mockWebServer.takeRequest()
    and:
      poll()
      def req2 = mockWebServer.takeRequest()
      def interval = client.pollingInterval
    then:
      req1.requestUrl.queryParameter("contextSha") == "0"
      etag == "etag12345"
      interval == 20
      req2.getHeader("if-none-match") == "etag12345"
      client.stopped
  }

  def "a 400 request prevents further requests"() {
    given: "a response"
      mockWebServer.enqueue(new MockResponse().with {
        setResponseCode(400)
      })
    when:
      poll()
    then:
      !client.canMakeRequests()
  }

  def "a 404 request prevents further requests"() {
    given: "a response"
      mockWebServer.enqueue(new MockResponse().with {
        setResponseCode(404)
      })
    when:
      poll()
    then:
      !client.canMakeRequests()
  }

  def "a 500 does not prevent requests but is ignored"() {
    given: "a response"
      mockWebServer.enqueue(new MockResponse().with {
        setResponseCode(500)
      })
    when:
      poll()
    then:
      client.canMakeRequests()
  }

  def "a context header causes the connection to be tried with a contextSha"() {
    given: "a response"
      mockWebServer.enqueue(new MockResponse().with {
        setResponseCode(500)
      })
    when:
      client.contextChange("header1", "sha-value")
      sleep(500)
      def req1 = mockWebServer.takeRequest()
    then:
      req1.requestUrl.queryParameter("contextSha") == "sha-value"
      req1.requestUrl.queryParameterValues("apiKey") == ["one", "two"]
      req1.getHeader("x-featurehub") == "header1"
  }
}
