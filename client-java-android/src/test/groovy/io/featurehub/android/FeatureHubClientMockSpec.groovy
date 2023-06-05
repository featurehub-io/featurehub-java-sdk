package io.featurehub.android

import com.fasterxml.jackson.databind.ObjectMapper
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.client.Readiness
import io.featurehub.sse.model.FeatureEnvironmentCollection
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import spock.lang.Specification

class FeatureHubClientMockSpec extends Specification {
  MockWebServer mockWebServer
  FeatureHubClient client
  FeatureHubConfig config
  InternalFeatureRepository repo
  ObjectMapper mapper

  def setup() {
    mapper = new ObjectMapper()
    config = Mock()
    repo = Mock()
    config.repository >> repo
    mockWebServer = new MockWebServer()

    def url = mockWebServer.url("/").toString()
    config.baseUrl() >> url.substring(0, url.length() - 1)
    config.apiKeys() >> ["one", "two"]
    config.serverEvaluation >> true
    client = new FeatureHubClient(config, 0)
    mockWebServer.url("/features")
  }

  def cleanup() {
    client.close()
    mockWebServer.shutdown()
  }

  def "a request for a known feature set with zero features"() {
    given: "a response"
      mockWebServer.enqueue(new MockResponse().with {
        setBody(mapper.writeValueAsString([new FeatureEnvironmentCollection().id(UUID.randomUUID()).features([])]))
        setResponseCode(200)
      })
    when:
      client.poll().get()
    then:
      1 * repo.updateFeatures([])
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
      def future = client.poll()
      def req1 = mockWebServer.takeRequest()
      future.get()
      def etag = client.etag
    and:
      def future2 = client.poll()
      def req2 = mockWebServer.takeRequest()
      future2.get()
      def interval = client.pollingInterval
    then:
      2 * repo.updateFeatures([])
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
      def future = client.poll()
      mockWebServer.takeRequest()
      future.get()
    then:
      !client.canMakeRequests()
  }

  def "a 404 request prevents further requests"() {
    given: "a response"
      mockWebServer.enqueue(new MockResponse().with {
        setResponseCode(404)
      })
    when:
      def future = client.poll()
      mockWebServer.takeRequest()
      future.get()
    then:
      !client.canMakeRequests()
  }

  def "a 500 does not prevent requests but is ignored"() {
    given: "a response"
      mockWebServer.enqueue(new MockResponse().with {
        setResponseCode(500)
      })
    when:
      def future = client.poll()
      mockWebServer.takeRequest()
      def result = future.get()
    then:
      result == Readiness.NotReady
      1 * repo.getReadiness() >> Readiness.NotReady
    when: "followed by a success"
      mockWebServer.enqueue( new MockResponse().with {
        setBody(mapper.writeValueAsString([new FeatureEnvironmentCollection().id(UUID.randomUUID()).features([])]))
        setHeader("etag", "etag12345")
        setResponseCode(200)
      })
    and:
      client.poll().get()
    then:
      client.canMakeRequests()
      1 * repo.getReadiness() >> Readiness.Ready
      1 * repo.updateFeatures(_)
  }

  def "a context header causes the connection to be tried with a contextSha"() {
    given: "a response"
      mockWebServer.enqueue(new MockResponse().with {
        setResponseCode(500)
      })
    when:
      def future = client.contextChange("header1", "sha-value")
      def req1 = mockWebServer.takeRequest()
      future.get()
    then:
      req1.requestUrl.queryParameter("contextSha") == "sha-value"
      req1.requestUrl.queryParameterValues("apiKey") == ["one", "two"]
      req1.getHeader("x-featurehub") == "header1"
  }
}
