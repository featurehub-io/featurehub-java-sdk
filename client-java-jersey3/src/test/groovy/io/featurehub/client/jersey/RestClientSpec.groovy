package io.featurehub.client.jersey

import cd.connect.openapi.support.ApiResponse
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.client.Readiness
import io.featurehub.sse.model.FeatureEnvironmentCollection
import io.featurehub.sse.model.SSEResultState
import spock.lang.Specification

import jakarta.ws.rs.core.Response

class RestClientSpec extends Specification {
  FeatureService featureService
  RestClient client
  InternalFeatureRepository repo
  FeatureHubConfig config
  List<String> apiKeys

  def setup() {
    apiKeys = ["123"]
    featureService = Mock()
    repo = Mock()
    config = Mock()
    config.isServerEvaluation() >> true
    client = new RestClient(repo, featureService, config, 0, false)
  }

  ApiResponse<List<FeatureEnvironmentCollection>> build(int statusCode = 200, List<FeatureEnvironmentCollection> data = [], Map<String, String> headers = [:]) {
    def response = Response.status(statusCode)

    if (data != null)
      response.entity(data)
    if (!headers?.isEmpty()) {
      headers.forEach { key, value -> response.header(key, value)}
    }

    return new ApiResponse<List<FeatureEnvironmentCollection>>(statusCode, null, data, response.build())
  }

  def "a basic poll with a 200 result"() {
    given:
      def response = build()
    when:
      client.poll().get()
    then:
      1 * repo.updateFeatures([])
      1 * config.apiKeys() >> apiKeys
//      1 * config.isServerEvaluation() >> true
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.readiness >> Readiness.Ready
      0 * _
  }

  def "a basic poll with a 236 result will cause the client to stop"() {
    given:
      def response = build(236)
    when:
      def result = client.poll().get()
    then:
      1 * repo.updateFeatures([])
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.readiness >> Readiness.Ready
      0 * _
      client.stopped
      result == Readiness.Ready
  }

  def "a poll with a 5xx result will cause the client to complete and not change readiness"() {
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

  def "a poll with a 400 result will cause the client to stop polling and indicate failure"() {
    given:
      def response = build(400)
      def apiKeys = ["123"]
    when:
      def result = client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * repo.notify(SSEResultState.FAILURE)
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.readiness >> Readiness.Failed
      0 * _
      client.stopped
      result == Readiness.Failed
  }

  def "change the header to itself and it won't run again"() {
    given:
      def response = build()
    when:
      def result = client.poll().get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.readiness >> Readiness.Ready
    when:
      def result2 = client.contextChange('new-header', '765').get()
    then:
      1 * config.apiKeys() >> apiKeys
      1 * repo.readiness >> Readiness.Ready
      1 * featureService.getFeatureStates(apiKeys, '765', ['x-featurehub': 'new-header']) >> response
  }

  def "cache header will change the polling interval"() {
    given:
      def response = build(200, [], ['cache-control': 'blah, max-age=300'])
    when:
      def result = client.poll().get()
    then:
      1 * repo.updateFeatures([])
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      1 * repo.readiness >> Readiness.Ready
      client.pollingInterval == 300
      0 * _

  }

  def "change the polling interval to 180 seconds and a second poll won't poll"() {
    given:
      def response = build()
      client = new RestClient(repo, featureService, config, 180, false)
    when:
      def result = client.poll().get()
      def result2 = client.poll().get()
    then:
      1 * repo.updateFeatures([])
      1 * config.apiKeys() >> apiKeys
      1 * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      2 * repo.readiness >> Readiness.Ready
      0 * _
  }

  def "change polling interval to 180 seconds and force breaking cache on every check"() {
    given:
      def response = build()
      client = new RestClient(repo, featureService, config, 180, true)
    when:
      client.poll().get()
      client.poll().get()
    then:
      2 * repo.updateFeatures([])
      2 * config.apiKeys() >> apiKeys
      2  * featureService.getFeatureStates(apiKeys, '0', [:]) >> response
      2 * repo.readiness >> Readiness.Ready
      0 * _
  }
}
