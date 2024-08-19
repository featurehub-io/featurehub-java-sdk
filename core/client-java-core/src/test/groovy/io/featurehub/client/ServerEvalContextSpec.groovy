package io.featurehub.client

import spock.lang.Specification

import java.util.concurrent.CompletableFuture

class ServerEvalContextSpec extends Specification {
  FeatureHubConfig config
  InternalFeatureRepository repo
  EdgeService edge

  def setup() {
    config = Mock(FeatureHubConfig)
    repo = Mock(InternalFeatureRepository)
    edge = Mock(EdgeService)
  }

  def "a server eval context should allow a build which should trigger a poll"() {
    given: "i have the requisite setup"
      def scc = new ServerEvalFeatureContext(repo, edge)
    when: "i attempt to build"
      scc.build().get();
      scc.userKey("fred").build().get()
      scc.clear().build().get()
    then: ""
      2 * repo.repositoryNotReady()
      2 * edge.contextChange(null, '0') >> {
        def future = new CompletableFuture<>()
        future.complete(scc)
        return future
      }
      1 * edge.contextChange("userkey=fred", '6a1d1fa42d1c1917552a255a940792205cb62cc2efd6613ab5a3f75d0038518b') >> {
        return CompletableFuture.completedFuture(scc)
      }
      3 * repo.execute { Runnable cmd -> cmd.run() }
      0 * _
  }
}
