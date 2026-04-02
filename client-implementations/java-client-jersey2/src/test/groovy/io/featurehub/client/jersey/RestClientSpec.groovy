package io.featurehub.client.jersey

import cd.connect.openapi.support.ApiResponse
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.client.Readiness
import io.featurehub.client.edge.EdgeRetryService
import io.featurehub.sse.model.FeatureEnvironmentCollection
import io.featurehub.sse.model.SSEResultState
import spock.lang.Specification

import javax.ws.rs.RedirectionException
import javax.ws.rs.core.Response

class RestClientSpec extends Specification {
  FeatureService featureService
  RestClient client
  InternalFeatureRepository repo
  FeatureHubConfig config
  List<String> apiKeys
  EdgeRetryService retryer

  def setup() {
    apiKeys = ["123"]
    featureService = Mock()
    repo = Mock()
    config = Mock()
    retryer = Mock()
    config.isServerEvaluation() >> true
    client = new RestClient(repo, featureService, config, retryer, 0)
  }

  ApiResponse<List<FeatureEnvironmentCollection>> build(int statusCode = 200, List<FeatureEnvironmentCollection> data = [], Map<String, String> headers = [:]) {
    def response = Response.status(statusCode)

    if (data != null)
      response.entity(data)
    if (!headers?.isEmpty()) {
      headers.forEach { key, value -> response.header(key, value) }
    }

    return new ApiResponse<List<FeatureEnvironmentCollection>>(statusCode, null, data, response.build())
  }

  // ---------------------------------------------------------------------------
  // poll() — status code handling
  // ---------------------------------------------------------------------------

  def "a 200 poll updates the repository and returns readiness"() {
    given:
      def response = build()
    when:
      def result = client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.updateFeatures([], "polling")
      1 * repo.readiness >> Readiness.Ready
      0 * _
      result == Readiness.Ready
  }

  def "a 236 poll updates the repository and stops the client"() {
    given:
      def response = build(236)
    when:
      def result = client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.updateFeatures([], "polling")
      1 * repo.readiness >> Readiness.Ready
      0 * _
      client.stopped
      result == Readiness.Ready
  }

  def "a 5xx poll does not stop the client and does not notify failure"() {
    given:
      def response = build(503)
    when:
      def result = client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.readiness >> Readiness.NotReady
      0 * _
      !client.stopped
      result == Readiness.NotReady
  }

  def "a 400 poll stops the client and notifies failure"() {
    given:
      def response = build(400)
    when:
      def result = client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.notify(SSEResultState.FAILURE, "polling")
      1 * repo.readiness >> Readiness.Failed
      0 * _
      client.stopped
      result == Readiness.Failed
  }

  def "a 404 poll stops the client and notifies failure"() {
    given:
      def response = build(404)
    when:
      def result = client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.notify(SSEResultState.FAILURE, "polling")
      1 * repo.readiness >> Readiness.Failed
      0 * _
      client.stopped
      result == Readiness.Failed
  }

  // ---------------------------------------------------------------------------
  // 304 handling — Jersey throws RedirectionException for 304
  // ---------------------------------------------------------------------------

  def "a 304 response is silently ignored — no update, no failure notification"() {
    given:
      def re = new RedirectionException(Response.status(304).build())
    when:
      def result = client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> { throw re }
      1 * repo.readiness >> Readiness.Ready
      0 * repo.notify(_, _)
      0 * repo.updateFeatures(_, _)
      result == Readiness.Ready
  }

  def "a non-304 redirection exception is treated as a failure"() {
    given:
      def re = new RedirectionException(Response.status(301).build())
    when:
      def result = client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> { throw re }
      1 * repo.notify(SSEResultState.FAILURE, "polling")
      1 * repo.readiness >> Readiness.Failed
      result == Readiness.Failed
  }

  // ---------------------------------------------------------------------------
  // ETag handling
  // ---------------------------------------------------------------------------

  def "etag from response is sent as if-none-match on the next poll"() {
    given:
      def firstResponse = build(200, [], ['etag': 'abc123'])
      def secondResponse = build(200)
    when:
      client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> firstResponse
      1 * repo.updateFeatures([], "polling")
      1 * repo.readiness >> Readiness.Ready
    when:
      client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', ['if-none-match': 'abc123']) >> secondResponse
      1 * repo.updateFeatures([], "polling")
      1 * repo.readiness >> Readiness.Ready
  }

  // ---------------------------------------------------------------------------
  // Cache-Control handling
  // ---------------------------------------------------------------------------

  def "cache-control max-age header updates the polling interval"() {
    given:
      def response = build(200, [], ['cache-control': 'blah, max-age=300'])
    when:
      client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.updateFeatures([], "polling")
      1 * repo.readiness >> Readiness.Ready
      0 * _
      client.pollingInterval == 300
  }

  def "cache-control max-age of zero does not change the polling interval"() {
    given:
      def response = build(200, [], ['cache-control': 'max-age=0'])
      client = new RestClient(repo, featureService, config, retryer, 60)
    when:
      client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.updateFeatures([], "polling")
      1 * repo.readiness >> Readiness.Ready
      0 * _
      client.pollingInterval == 60
  }

  // ---------------------------------------------------------------------------
  // contextChange
  // ---------------------------------------------------------------------------

  def "contextChange stores context header and sha then polls with them"() {
    given:
      def response = build()
    when:
      def result = client.contextChange('user-context', 'sha123').get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, 'sha123', ['x-featurehub': 'user-context']) >> response
      1 * repo.updateFeatures([], "polling")
      1 * repo.readiness >> Readiness.Ready
      0 * _
      result == Readiness.Ready
  }

  def "contextChange with a null header does not send x-featurehub"() {
    given:
      def response = build()
    when:
      client.contextChange(null, 'sha123').get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, 'sha123', [:]) >> response
      1 * repo.updateFeatures([], "polling")
      1 * repo.readiness >> Readiness.Ready
      0 * _
  }

  // ---------------------------------------------------------------------------
  // needsContextChange
  // ---------------------------------------------------------------------------

  def "needsContextChange returns true when etag is null (never polled)"() {
    expect:
      client.needsContextChange('any-header', 'sha') == true
  }

  def "needsContextChange returns true when repository is not Ready"() {
    given:
      client.setEtag('abc123')
    when:
      def result = client.needsContextChange('header', 'sha')
    then:
      1 * repo.readiness >> Readiness.NotReady
      result == true
  }

  def "needsContextChange returns true in server-eval mode when context header differs from last sent"() {
    given:
      client.setEtag('abc123')
      // xFeaturehubHeader is null — any non-null header counts as a change
    when:
      def result = client.needsContextChange('new-header', 'sha')
    then:
      1 * repo.readiness >> Readiness.Ready
      result == true
  }

  def "needsContextChange returns false in server-eval mode when context header is unchanged"() {
    given:
      // prime xFeaturehubHeader and etag via a real contextChange + poll
      def response = build(200, [], ['etag': 'abc123'])
      config.apiKeys() >> apiKeys
      featureService.getFeatureStates(_, _, _) >> response
      repo.readiness >> Readiness.Ready
      client.contextChange('same-header', 'sha').get()
    when:
      def result = client.needsContextChange('same-header', 'sha')
    then:
      result == false
  }

  def "needsContextChange returns false in client-eval mode regardless of context header"() {
    given:
      def clientEvalConfig = Mock(FeatureHubConfig)
      clientEvalConfig.isServerEvaluation() >> false
      clientEvalConfig.apiKeys() >> apiKeys
      def clientEvalClient = new RestClient(repo, featureService, clientEvalConfig, retryer, 0)
      clientEvalClient.setEtag('abc123')
    when:
      def result = clientEvalClient.needsContextChange('any-header', 'sha')
    then:
      1 * repo.readiness >> Readiness.Ready
      result == false
  }

  def "needsContextChange ignores the contextSha parameter"() {
    given:
      // prime xFeaturehubHeader to 'header' and set etag so ready state is established
      def response = build(200, [], ['etag': 'abc123'])
      config.apiKeys() >> apiKeys
      featureService.getFeatureStates(_, _, _) >> response
      repo.readiness >> Readiness.Ready
      client.contextChange('header', 'sha-A').get()
    when: "called with two different sha values but the same header"
      def result1 = client.needsContextChange('header', 'sha-A')
      def result2 = client.needsContextChange('header', 'sha-B')
    then:
      // sha is irrelevant — same header means no context change needed
      !result1
      !result2
  }
}