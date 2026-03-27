package io.featurehub.client

import com.fasterxml.jackson.databind.ObjectMapper
import io.featurehub.client.usage.UsageProvider
import io.featurehub.javascript.JavascriptObjectMapper
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException
import java.util.function.Consumer

class EdgeFeatureHubConfigSpec extends Specification {
  FeatureHubConfig config
  EdgeService edgeClient

  def setup() {
    config = new EdgeFeatureHubConfig("http://localhost", "${UUID.randomUUID()}/123*abc")
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

  def "closing after a newContext marks isClosed and prevents re-opening"() {
    when:
      config.newContext()
      config.close()
      config.newContext()
    then:
      thrown(ConfigurationClosedException)
      1 * edgeClient.close()
      0 * _
  }

  def "all the passthrough on the repository from the config works as expected"() {
    given: "i have mocked the repository and set it"
      def repo = Mock(InternalFeatureRepository)
      config.setRepository(repo)
    and: "I have some values ready to set"
      def om = Mock(JavascriptObjectMapper)
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
      config = new EdgeFeatureHubConfig("http://localhost", "${UUID.randomUUID()}/2*3")
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
      def config = new EdgeFeatureHubConfig("http://localhost", "${UUID.randomUUID()}/123-abc")
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
      def apiKey = "${UUID.randomUUID()}/123-abc"
      def config = new EdgeFeatureHubConfig("http://localhost/", apiKey)
    then:
      config.apiKey() == apiKey
      config.baseUrl() == 'http://localhost'
      config.realtimeUrl == "http://localhost/features/${apiKey}"
      config.isServerEvaluation()
  }

  def "initialising detects client evaluated context"() {
    when: "i have a client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost/", "${UUID.randomUUID()}/123*abc")
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

  def "init completes successfully if future resolves within the given time"() {
    given: "A mock future that completes successfully"
      def futureContext = Mock(Future<ClientContext>)
      def mockContext = Mock(ClientContext)
    and: "I mock the context and future"
      def clientContext = Mock(ClientContext)

    and: "A client eval feature config"
      def config = new EdgeFeatureHubConfig("http://localhost/", "${UUID.randomUUID()}/123*abc") {
        @Override
        ClientContext newContext() {
          return clientContext
        }
      }
    when: "init is called with a reasonable timeout"
      config.init(100, TimeUnit.MILLISECONDS)
    then: "The get method on the future should be called with timeout"
      1 * futureContext.get(100, TimeUnit.MILLISECONDS) >> mockContext
      1 * clientContext.build() >> futureContext
      0 * _
  }

  def "init should timeout if future does not complete within the given time"() {
    given: "A mock future that completes successfully"
    def futureContext = Mock(Future<ClientContext>)
    def mockContext = Mock(ClientContext)
    and: "I mock the context and future"
    def clientContext = Mock(ClientContext)

    and: "A client eval feature config"
    def config = new EdgeFeatureHubConfig("http://localhost/", "${UUID.randomUUID()}/123*abc") {
      @Override
      ClientContext newContext() {
        return clientContext
      }
    }
    when: "init is called with a very short timeout"
      config.init(1, TimeUnit.MILLISECONDS)
    then: "The get method on the future should be called with timeout"
      1 * futureContext.get(1, TimeUnit.MILLISECONDS) >> { throw new TimeoutException() }
      1 * clientContext.build() >> futureContext
      0 * _
  }

  // --- waitForReady tests ---

  def "waitForReady returns true immediately when already ready"() {
    given:
      def repo = Mock(InternalFeatureRepository)
      config.setRepository(repo)
      repo.getReadiness() >> Readiness.Ready
    when:
      def result = config.waitForReady(1, TimeUnit.SECONDS)
    then:
      result
      1 * edgeClient.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
  }

  def "waitForReady returns false when timeout elapses before ready"() {
    given:
      def repo = Mock(InternalFeatureRepository)
      config.setRepository(repo)
      repo.getReadiness() >> Readiness.NotReady
    when:
      def result = config.waitForReady(250, TimeUnit.MILLISECONDS)
    then:
      !result
      1 * edgeClient.poll() >> CompletableFuture.completedFuture(Readiness.NotReady)
  }

  def "waitForReady polls edge service and returns true when readiness transitions to Ready"() {
    given:
      def repo = Mock(InternalFeatureRepository)
      config.setRepository(repo)
      def callCount = 0
      repo.getReadiness() >> { callCount++ < 2 ? Readiness.NotReady : Readiness.Ready }
    when:
      def result = config.waitForReady(2, TimeUnit.SECONDS)
    then:
      result
      1 * edgeClient.poll() >> CompletableFuture.completedFuture(Readiness.NotReady)
  }

  def "waitForReady throws ConfigurationClosedException after close"() {
    given:
      config.close()
    when:
      config.waitForReady(1, TimeUnit.SECONDS)
    then:
      thrown(ConfigurationClosedException)
  }

  def "waitForReady creates edge service if newContext has not been called yet"() {
    given:
      def repo = Mock(InternalFeatureRepository)
      config.setRepository(repo)
      repo.getReadiness() >> Readiness.Ready
    when: "waitForReady is called without a prior newContext"
      def result = config.waitForReady(1, TimeUnit.SECONDS)
    then:
      result
      1 * edgeClient.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
  }

  // --- closed-state tests ---

  def "isClosed is false before close and true after"() {
    expect:
      !config.isClosed()
    when:
      config.close()
    then:
      config.isClosed()
  }

  def "close is idempotent"() {
    when:
      config.close()
      config.close()
    then:
      noExceptionThrown()
  }

  def "getReadiness returns NotReady after close"() {
    when:
      config.close()
    then:
      config.getReadiness() == Readiness.NotReady
  }

  def "getRepository and getInternalRepository return null after close"() {
    when:
      config.close()
    then:
      config.getRepository() == null
      config.getInternalRepository() == null
  }

  def "getEdgeService returns null after close"() {
    when:
      config.close()
    then:
      config.getEdgeService() == null
  }

  def "init throws ConfigurationClosedException after close"() {
    given:
      config.close()
    when:
      config.init()
    then:
      thrown(ConfigurationClosedException)
  }

  def "init with timeout throws ConfigurationClosedException after close"() {
    given:
      config.close()
    when:
      config.init(1, TimeUnit.SECONDS)
    then:
      thrown(ConfigurationClosedException)
  }

  def "addReadinessListener throws ConfigurationClosedException after close"() {
    given:
      config.close()
    when:
      config.addReadinessListener({ } as Consumer<Readiness>)
    then:
      thrown(ConfigurationClosedException)
  }

  def "registerValueInterceptor throws ConfigurationClosedException after close"() {
    given:
      config.close()
    when:
      config.registerValueInterceptor(Mock(ExtendedFeatureValueInterceptor))
    then:
      thrown(ConfigurationClosedException)
  }

  def "registerRawUpdateFeatureListener throws ConfigurationClosedException after close"() {
    given:
      config.close()
    when:
      config.registerRawUpdateFeatureListener(Mock(RawUpdateFeatureListener))
    then:
      thrown(ConfigurationClosedException)
  }

  def "fluent configuration setters are silently ignored after close"() {
    when:
      config.close()
    then:
      config.streaming() == config
      config.restActive() == config
      config.restPassive() == config
      config.setEdgeService({ null }) == config
      config.setJsonConfigObjectMapper(Mock(JavascriptObjectMapper)) == config
      config.recordUsageEvent(null) == config
      noExceptionThrown()
  }

  def "apiKey, baseUrl and isServerEvaluation still work after close"() {
    when:
      config.close()
    then:
      config.apiKey() != null
      config.baseUrl() == 'http://localhost'
      !config.isServerEvaluation()
  }
}
