package io.featurehub.okhttp

import com.fasterxml.jackson.databind.ObjectMapper
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.client.Readiness
import io.featurehub.client.edge.EdgeRetryer
import io.featurehub.javascript.Jackson2ObjectMapper
import io.featurehub.javascript.JavascriptObjectMapper
import io.featurehub.sse.model.FeatureEnvironmentCollection
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import spock.lang.Specification

class RestClientSpec extends Specification {
  RestClient client
  MockWebServer mockWebServer
  FeatureHubConfig config
  InternalFeatureRepository repo
  ObjectMapper mapper
  JavascriptObjectMapper fhMapper

  def setup() {
    mapper = new ObjectMapper()
    fhMapper = new Jackson2ObjectMapper()
    config = Mock()
    repo = Mock()
    repo.getJsonObjectMapper() >> fhMapper
    config.repository >> repo
    mockWebServer = new MockWebServer()

    def url = mockWebServer.url("/").toString()
    config.baseUrl() >> url.substring(0, url.length() - 1)
    config.apiKeys() >> ["one", "two"]
    config.serverEvaluation >> true
    client = new RestClient(config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().rest().build(), 0)
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
      1 * repo.updateFeatures([], "polling")
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
      2 * repo.updateFeatures([], "polling")
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
      1 * repo.updateFeatures(_, "polling")
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

  def "a 401 request prevents further requests"() {
    given:
      mockWebServer.enqueue(new MockResponse().with {
        setResponseCode(401)
      })
    when:
      def future = client.poll()
      mockWebServer.takeRequest()
      future.get()
    then:
      !client.canMakeRequests()
  }

  def "a 403 request prevents further requests"() {
    given:
      mockWebServer.enqueue(new MockResponse().with {
        setResponseCode(403)
      })
    when:
      def future = client.poll()
      mockWebServer.takeRequest()
      future.get()
    then:
      !client.canMakeRequests()
  }

  def "a 304 response is silently ignored — no update, no failure notification"() {
    given:
      mockWebServer.enqueue(new MockResponse().with {
        setResponseCode(304)
      })
    when:
      def future = client.poll()
      mockWebServer.takeRequest()
      def result = future.get()
    then:
      result == Readiness.Ready
      1 * repo.getReadiness() >> Readiness.Ready
      0 * repo.updateFeatures(_, _)
      0 * repo.notify(_, _)
  }

  // ---------------------------------------------------------------------------
  // needsContextChange — unit tests (no network required)
  // ---------------------------------------------------------------------------

  def "needsContextChange returns true when no prior poll has set an etag"() {
    // etag is null by default — always triggers a poll to get initial data
    expect:
      client.needsContextChange('any-header', 'any-sha')
  }

  def "needsContextChange returns true when repository is not yet ready"() {
    given:
      client.setEtag("etag-abc")
      // repo.getReadiness() not stubbed → returns null → null != Readiness.Ready → true
    expect:
      client.needsContextChange('any-header', 'any-sha')
  }

  def "needsContextChange returns true for server-eval client when header has changed from current"() {
    given: "etag set, repo ready, server-eval (setup default), header differs from current null"
      client.setEtag("etag-abc")
      repo.getReadiness() >> Readiness.Ready
      // xFeaturehubHeader starts as null; 'new-header' != null is a change
    expect:
      client.needsContextChange('new-header', 'any-sha')
  }

  def "needsContextChange returns false for server-eval client when header is unchanged"() {
    given:
      client.setEtag("etag-abc")
      repo.getReadiness() >> Readiness.Ready
      client.@xFeaturehubHeader = 'current-header'
    expect:
      !client.needsContextChange('current-header', 'any-sha')
  }

  def "needsContextChange returns false when newHeader is null — no user context to push"() {
    given:
      client.setEtag("etag-abc")
      repo.getReadiness() >> Readiness.Ready
    expect:
      !client.needsContextChange(null, 'any-sha')
  }

  def "needsContextChange returns false for client-eval client regardless of header change"() {
    given: "a client configured for client-side evaluation"
      def localConfig = Mock(FeatureHubConfig)
      def localRepo = Mock(InternalFeatureRepository)
      localRepo.getJsonObjectMapper() >> fhMapper
      localConfig.repository >> localRepo
      def url = mockWebServer.url("/").toString()
      localConfig.baseUrl() >> url.substring(0, url.length() - 1)
      localConfig.apiKeys() >> ["one"]
      localConfig.isServerEvaluation() >> false   // client-eval
      def localClient = new RestClient(localRepo, localConfig,
        EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().rest().build(), 0, false)
      localClient.setEtag("etag-abc")
      localRepo.getReadiness() >> Readiness.Ready
    expect:
      !localClient.needsContextChange('any-header', 'any-sha')
    cleanup:
      localClient.close()
  }
}
