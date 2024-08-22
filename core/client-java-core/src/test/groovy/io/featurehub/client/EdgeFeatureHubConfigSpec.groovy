package io.featurehub.client

import com.fasterxml.jackson.databind.ObjectMapper
import io.featurehub.client.usage.UsageProvider
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.function.Consumer

class EdgeFeatureHubConfigSpec extends Specification {
  FeatureHubConfig config
  EdgeService edgeClient

  def setup() {
    config = new EdgeFeatureHubConfig("http://localhost", "123*abc")
    edgeClient = Mock(EdgeService)
    config.setEdgeService { -> edgeClient }
  }

  def "i can create a valid client evaluated config and multiple requests for a a new context will result in a single connection"() {
    when: "i ask for a new context"
      def ctx1 = config.newContext()
    and: "i ask again"
      def ctx2 = config.newContext()
    then:
      0 * _
  }

  def "if we use a client eval key, closing after a newContext and re-opening will get a new connection"() {
    when: "i ask for a new context"
      def ctx1 = config.newContext()
      config.close()
    and: "i ask again"
      def ctx2 = config.newContext()
    then:
      ctx1 != null
      ctx2 != null
      ctx1 != ctx2
      config.edgeService.get() == edgeClient
      1 * edgeClient.close()
      0 * _
  }

  def "all the passthrough on the repository from the config works as expected"() {
    given: "i have mocked the repository and set it"
      def repo = Mock(InternalFeatureRepository)
      config.setRepository(repo)
    and: "I have some values ready to set"
      def om = new ObjectMapper()
      Consumer<Readiness> readynessListener = Mock(Consumer<Readiness>)
      def featureValueOverride = Mock(FeatureValueInterceptor)
      def analyticsProvider = Mock(UsageProvider)
    when: "i set all the passthrough settings"
      config.setJsonConfigObjectMapper(om)
      config.addReadinessListener(readynessListener)
      config.registerValueInterceptor(false, featureValueOverride)
    then:
      1 * repo.registerValueInterceptor(false, featureValueOverride)
      1 * repo.addReadinessListener(readynessListener) >> Mock(RepositoryEventHandler)
      1 * repo.setJsonConfigObjectMapper(om)
      0 * _  // nothing else
  }

  def "when i create a client evaluated feature context it should auto find the provider"() {
    given: "i clean up the static provider"
      FeatureHubTestClientFactory.fake = null
      config = new EdgeFeatureHubConfig("http://localhost", "2*3")
    when: "i create a new client"
      def context = config.newContext()
    then:
      context instanceof ClientEvalFeatureContext
      context.repository == config.repository
      context.edgeService == FeatureHubTestClientFactory.fake
      context.edgeService.config == config
      0 * _
  }

  def "when i create a server evaluated feature context it should auto find the provider"() {
    given: "i have a client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost", "123-abc")
    when: "i create a new client"
      def context = config.newContext()
    and: "i create a second client"
      def context2 = config.newContext()
    then:
      context == context2
      0 * _
  }

  def "initialising gets the urls correct and detects server evaluated context"() {
    when: "i have a client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost/", "123-abc")
    then:
      config.apiKey() == '123-abc'
      config.baseUrl() == 'http://localhost'
      config.realtimeUrl == 'http://localhost/features/123-abc'
      config.isServerEvaluation()
  }

  def "initialising detects client evaluated context"() {
    when: "i have a client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost/", "123*abc")
    then:
      !config.isServerEvaluation()
  }

  def "default repository and edge service supplier work"() {
    when: "i have a client eval feature config"
      def ctx = config.newContext()
    then:
      config.repository instanceof ClientFeatureRepository
      config.readiness == Readiness.NotReady
      config.edgeService.get() == edgeClient
  }

  def "i can pre-replace the repository and edge supplier and the context gets created as expected"() {
    given: "i have mocked the edge supplier"
      def mockRepo = Mock(InternalFeatureRepository)
      def executor = Mock(ExecutorService)
      config.setRepository(mockRepo)
    when:
      def ctx  = config.init().get() as BaseClientContext
    then:
      ctx.edgeService == edgeClient
      ctx.repository == mockRepo
      1 * edgeClient.contextChange(null, '0') >> CompletableFuture.completedFuture(Readiness.Ready)
      1 * mockRepo.getExecutor() >> executor
      1 * executor.execute { Runnable r -> r.run() }
      0 * _
  }
}
