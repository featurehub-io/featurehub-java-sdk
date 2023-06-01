package io.featurehub.client

import spock.lang.Specification

import java.util.concurrent.CompletableFuture

class ServerEvalContextSpec extends Specification {
  def config
  def repo
  def edge

  def setup() {
    config = Mock(FeatureHubConfig)
    repo = Mock(FeatureRepositoryContext)
    edge = Mock(EdgeService)
  }

  def "a server eval context should allow a build which should trigger a poll"() {
    given: "i have the requisite setup"
      def scc = new ServerEvalFeatureContext(config, repo, { -> edge})
      edge.isRequiresReplacementOnHeaderChange() >> false
    when: "i attempt to build"
      scc.build();
      scc.userKey("fred").build()
      scc.clear().build();
    then: ""
      2 * repo.repositoryNotReady()
      2 * edge.isRequiresReplacementOnHeaderChange()
      2 * edge.contextChange(null, '0') >> {
        def future = new CompletableFuture<>()
        future.complete(scc)
        return future
      }
      1 * edge.contextChange("userkey=fred", '6a1d1fa42d1c1917552a255a940792205cb62cc2efd6613ab5a3f75d0038518b') >> {
        def future = new CompletableFuture<>()
        future.complete(scc)
        return future
      }
      0 * _
  }
}
