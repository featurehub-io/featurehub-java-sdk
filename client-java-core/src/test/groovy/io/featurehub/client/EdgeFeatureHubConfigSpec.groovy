package io.featurehub.client

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

import java.util.concurrent.Future
import java.util.function.Supplier

class EdgeFeatureHubConfigSpec extends Specification {
  def "i can create a valid client evaluated config and multiple requests for a a new context will result in a single connection"() {
    given: "i have a client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost", "123*abc")
    and: "i have configured a edge provider"
      Supplier<EdgeService> edgeSupplier = Mock(Supplier<EdgeService>)
      def edgeClient = Mock(EdgeService)
    when: "i ask for a new context"
      def ctx1 = config.newContext(null, edgeSupplier)
    and: "i ask again"
      def ctx2 = config.newContext(null, edgeSupplier)
    then:
      1 * edgeSupplier.get() >> edgeClient
      0 * _
  }

  def "if we use a client eval key, closing after a newContext and re-opening will get a new connection"() {
    given: "i have a client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost", "123*abc")
    and: "i have configured a edge provider"
      Supplier<EdgeService> edgeSupplier = Mock(Supplier<EdgeService>)
      def edgeClient = Mock(EdgeService)
    when: "i ask for a new context"
      def ctx1 = config.newContext(null, edgeSupplier)
      config.close()
    and: "i ask again"
      def ctx2 = config.newContext(null, edgeSupplier)
    then:
      2 * edgeSupplier.get() >> edgeClient
      1 * edgeClient.close()
      0 * _
  }

  def "all the passthrough on the repository from the config works as expected"() {
    given: "i have a client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost", "123*abc")
    and: "i have mocked the repository and set it"
      def repo = Mock(FeatureRepositoryContext)
      config.setRepository(repo)
    and: "I have some values ready to set"
      def om = new ObjectMapper()
      def readynessListener = Mock(ReadinessListener)
      def analyticsCollector = Mock(AnalyticsCollector)
      def featureValueOverride = Mock(FeatureValueInterceptor)
    when: "i set all the passthrough settings"
      config.setJsonConfigObjectMapper(om)
      config.addReadynessListener(readynessListener)
      config.addAnalyticCollector(analyticsCollector)
      config.registerValueInterceptor(false, featureValueOverride)
    then:
      1 * repo.registerValueInterceptor(false, featureValueOverride)
      1 * repo.addReadinessListener(readynessListener)
      1 * repo.addAnalyticCollector(analyticsCollector)
      1 * repo.setJsonConfigObjectMapper(om)
      0 * _  // nothing else
  }

  def "when i create a client evaluated feature context it should auto find the provider"() {
    given: "i have a client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost", "123*abc")
    and: "i clean up the static provider"
      FeatureHubTestClientFactory.repository = null
      FeatureHubTestClientFactory.config = null
      FeatureHubTestClientFactory.edgeServiceSupplier = Mock(Supplier<EdgeService>)
      def edgeClient = Mock(EdgeService)
    when: "i create a new client"
      def context = config.newContext()
    then:
      context instanceof ClientEvalFeatureContext
      1 * FeatureHubTestClientFactory.edgeServiceSupplier.get() >> edgeClient
      ((ClientEvalFeatureContext)context).edgeService == edgeClient
      0 * _
  }

  def "when i create a server evaluated feature context it should auto find the provider"() {
    given: "i have a client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost", "123-abc")
    and: "i clean up the static provider"
      FeatureHubTestClientFactory.repository = null
      FeatureHubTestClientFactory.config = null
      FeatureHubTestClientFactory.edgeServiceSupplier = Mock(Supplier<EdgeService>)
      def edgeClient = Mock(EdgeService)
    when: "i create a new client"
      def context = config.newContext()
    then:
      context instanceof ServerEvalFeatureContext
      ((ServerEvalFeatureContext)context).edgeService == null
      ((ServerEvalFeatureContext)context).edgeServiceSupplier == FeatureHubTestClientFactory.edgeServiceSupplier
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
    given: "i have mocked the edge supplier"
      FeatureHubTestClientFactory.edgeServiceSupplier = Mock(Supplier<EdgeService>)
    when: "i have a client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost/", "123*abc")
    then:
      config.repository instanceof ClientFeatureRepository
      config.readyness == Readiness.NotReady
      config.edgeService == FeatureHubTestClientFactory.edgeServiceSupplier
  }

  def "i can pre-replace the repository and edge supplier and the context gets created as expected"() {
    given: "i have mocked the edge supplier"
      def supplier = Mock(Supplier<EdgeService>)
      def client = Mock(EdgeService)
      FeatureHubTestClientFactory.edgeServiceSupplier = supplier
      def config = new EdgeFeatureHubConfig("http://localhost/", "123-abc")
      def repo = Mock(FeatureRepositoryContext)
      config.repository = repo
    and: "i mock out the futures"
      def mockRequest = Mock(Future<Readiness>)
    when:
      config.init()
    then:
      1 * supplier.get() >> client
      1 * client.contextChange(null, '0') >> mockRequest
      1 * mockRequest.get() >> Readiness.Ready
      0 * _
  }
}
