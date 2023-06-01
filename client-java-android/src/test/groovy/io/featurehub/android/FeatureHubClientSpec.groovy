package io.featurehub.android

import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import okhttp3.Call
import okhttp3.Request
import spock.lang.Specification

class FeatureHubClientSpec extends Specification {
  Call.Factory client
  Call call;
  InternalFeatureRepository repo
  FeatureHubClient fhc

  def "a null sdk url will never trigger a call"() {
    when: "i initialize the client"
      call = Mock()
      def fhc = new FeatureHubClient(null, null, null, client, Mock(FeatureHubConfig), 0)
    and: "check for updates"
      fhc.checkForUpdates(change)
    then:
      thrown RuntimeException
  }

  def "a valid host and url will trigger a call when asked"() {
    given: "i validly initialize the client"
      call = Mock()

      client = Mock {
        1 * newCall({ Request r ->
          r.header('x-featurehub') == 'fred=mary'
          r.header('if-none-match') == 'jimbo'
        }) >> call
      }

      repo = Mock {
      }
      fhc = new FeatureHubClient("http://localhost", ["1234"], repo, client, Mock(FeatureHubConfig), 0)
      fhc.etag = 'jimbo'
    and: "i specify a header"
      fhc.contextChange("fred=mary", "bonkers")
    when: "i check for updates"
      fhc.checkForUpdates(change)
    then:
      1 == 1
  }

  // can't test any further because okhttp uses too many final classes
  def "a response"() {

  }
}
