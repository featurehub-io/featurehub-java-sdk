package io.featurehub.client.edge

import io.featurehub.sse.model.SSEResultState
import io.featurehub.client.Readiness
import io.featurehub.client.InternalFeatureRepository
import spock.lang.Specification

import java.util.concurrent.ExecutorService

class EdgeRetryerSpec extends Specification {
  ExecutorService mockExecutor
  EdgeRetryer retryer
  int backoffBaseTime
  boolean backoffAdjustBackoff
  EdgeReconnector reconnector

  def setup() {
    mockExecutor = Mock(ExecutorService)
    reconnector = Mock(EdgeReconnector)
    retryer = new EdgeRetryer(100, 100, 100, 10, 100, 100) {
      @Override
      protected ExecutorService makeExecutorService() {
        return mockExecutor
      }

      @Override
      protected void backoff(int baseTime, boolean adjustBackoff) {
        // do nothing
        backoffBaseTime = baseTime
        backoffAdjustBackoff = adjustBackoff
      }
    }

  }

  def "a new backoff should always be greater than the backoff multiplier"() {
    when: "i ask for a new backup 100 times"
      def backoffs =
        (1..100).collect { retryer.newBackoff(it) }
    then: "it is always greater"
      (1..100).each { it < backoffs[it-1] }
  }

  def "the backoff will never go above the maximum backoff"() {
    when: "i calculate the new backoff"
      def newBackoff = retryer.calculateBackoff(90, 1000)
    then:
      newBackoff <= 100
  }

  // can't test any randomness

  def "if the server says 'bye' then we will not backoff and immediately try and reconnect"() {
    when: "i send a bye event"
      retryer.edgeResult(EdgeConnectionState.SERVER_SAID_BYE, reconnector )
    then:
      1 * reconnector.reconnect()
      1 * mockExecutor.submit({ Runnable task -> task.run()})
      1 * mockExecutor.isShutdown() >> false
      0 * _
  }

  def "if the server says 'disconnect' then we will backoff with the disconnect timeout and adjust backoff"() {
    when: "i send a disconnected event"
      retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, reconnector )
    then:
      backoffAdjustBackoff
      backoffBaseTime == retryer.serverDisconnectRetryMs
      1 * reconnector.reconnect()
      1 * mockExecutor.submit({ Runnable task -> task.run()})
  }

  def "if the server says 'connect timeout' then we will backoff with the connect timeout and adjust backoff"() {
    when: "i send a connect timeout event"
      retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, reconnector )
    then:
      backoffAdjustBackoff
      backoffBaseTime == retryer.serverReadTimeoutMs
      1 * reconnector.reconnect()
      1 * mockExecutor.submit({ Runnable task -> task.run()})
  }

  def "if the server says success we shouldn't schedule any tasks"() {
    when: "i send a success event"
      retryer.edgeResult(EdgeConnectionState.SUCCESS, reconnector )
    then:
      0 * reconnector.reconnect()
      0 * mockExecutor.submit(_)
  }

  def "if the executor service is shut down, no calls are ignored"() {
    when: "i send a connect timeout event"
      retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, reconnector )
    then:
      1 * mockExecutor.isShutdown() >> true
      0 * reconnector.reconnect()
  }

  def "if the not found is sent, then further requests are ignored"() {
    when: "i send the api not found event"
      retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, reconnector )
    and:  "i send a connect timeout event"
      retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, reconnector )
    then:
      1 * mockExecutor.isShutdown() >> false
      0 * reconnector.reconnect()

  }

  def "a cancelled reconnect doesn't reconnect, even once it has backed off"() {
    given: "a reconnect waiting to run"
      Runnable task = null
      mockExecutor.submit(_ as Runnable) >> { Runnable r -> task = r; Mock(java.util.concurrent.Future) }
      retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
    when:
      retryer.cancelReconnects()
      task.run()
    then:
      0 * reconnector.reconnect()
  }

  def "cancelling interrupts a reconnect that is backing off, so later reconnects aren't held up behind it"() {
    given: "a real single threaded retryer, with a long backoff in progress"
      def real = new EdgeRetryer(100, 100, 100, 10, 60000, 60000)
      def reconnected = new java.util.concurrent.CountDownLatch(1)
      def stale = Mock(EdgeReconnector)
      real.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, stale)
    when: "it is cancelled and a reconnect that doesn't back off is asked for"
      real.cancelReconnects()
      real.edgeResult(EdgeConnectionState.SERVER_SAID_BYE, { reconnected.countDown() } as EdgeReconnector)
    then: "it happens straight away"
      reconnected.await(5, java.util.concurrent.TimeUnit.SECONDS)
      0 * stale.reconnect()
    cleanup:
      real.close()
  }

  def "a cancelled backoff doesn't make later backoffs longer"() {
    given: "a real retryer backing off after a connection failure"
      def real = new EdgeRetryer(100, 100, 100, 10, 60000, 60000)
      def started = new java.util.concurrent.CountDownLatch(1)
      real.executorService.submit { started.countDown() }
      real.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
      started.await(5, java.util.concurrent.TimeUnit.SECONDS)
      Thread.sleep(100) // let the backoff start
    when:
      real.cancelReconnects()
      def done = new java.util.concurrent.CountDownLatch(1)
      real.executorService.submit { done.countDown() }
      done.await(5, java.util.concurrent.TimeUnit.SECONDS)
    then:
      real.currentBackoffMultiplier == 10
      0 * reconnector.reconnect()
    cleanup:
      real.close()
  }

  def "after a #state we always wait the bye backoff before reconnecting"() {
    given:
      def real = EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().withByeBackoffMs(300)
        .withServerDisconnectRetryMs(0).withServerByeReconnectMs(0).build()
      def reconnected = new java.util.concurrent.CompletableFuture<Long>()
      def start = System.currentTimeMillis()
    when:
      real.edgeResult(state, { reconnected.complete(System.currentTimeMillis()) } as EdgeReconnector)
    then:
      reconnected.get(5, java.util.concurrent.TimeUnit.SECONDS) - start >= 290
    cleanup:
      real.close()
    where:
      state << [EdgeConnectionState.SERVER_SAID_BYE, EdgeConnectionState.SERVER_WAS_DISCONNECTED]
  }

  def "a cancelled disconnect backoff doesn't reconnect"() {
    given:
      def real = EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().withByeBackoffMs(2000).build()
      def stale = Mock(EdgeReconnector)
      real.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, stale)
    when:
      real.cancelReconnects()
      def done = new java.util.concurrent.CountDownLatch(1)
      real.executorService.submit { done.countDown() }
    then: "it stops waiting straight away"
      done.await(1, java.util.concurrent.TimeUnit.SECONDS)
      0 * stale.reconnect()
    cleanup:
      real.close()
  }

  def "a cancelled bye backoff doesn't reconnect"() {
    given:
      def real = EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().withByeBackoffMs(2000).build()
      def stale = Mock(EdgeReconnector)
      real.edgeResult(EdgeConnectionState.SERVER_SAID_BYE, stale)
    when:
      real.cancelReconnects()
      def done = new java.util.concurrent.CountDownLatch(1)
      real.executorService.submit { done.countDown() }
    then: "it stops waiting straight away"
      done.await(1, java.util.concurrent.TimeUnit.SECONDS)
      0 * stale.reconnect()
    cleanup:
      real.close()
  }

  def "the bye backoff defaults to 300ms and can be configured with featurehub.edge.bye-backoff-ms"() {
    when:
      def defaulted = EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().build()
      System.setProperty("featurehub.edge.bye-backoff-ms", "50")
      def configured = EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().build()
    then:
      defaulted.byeBackoffMs == 300
      configured.byeBackoffMs == 50
    cleanup:
      System.clearProperty("featurehub.edge.bye-backoff-ms")
      defaulted?.close()
      configured?.close()
  }

  def "a subclass using the constructor without a bye backoff still gets the default one"() {
    when:
      def real = new EdgeRetryer(100, 100, 100, 10, 100, 100) {}
    then:
      real.byeBackoffMs == EdgeRetryer.DEFAULT_BYE_BACKOFF_MS
      EdgeRetryer.DEFAULT_BYE_BACKOFF_MS == 300
    cleanup:
      real.close()
  }

  def "a failure event only marks us failed if we never had features"() {
    given:
      def repository = Mock(InternalFeatureRepository)
      repository.getReadiness() >> readiness
    when:
      retryer.convertSSEState(SSEResultState.FAILURE, null, repository, null)
    then:
      notified * repository.notify(SSEResultState.FAILURE, "streaming")
    where:
      readiness          | notified
      Readiness.NotReady | 1
      Readiness.Ready    | 0
  }

  def "a reconnect cancelled after it has started is seen as cancelled on its own thread, and only there"() {
    given: "a real retryer whose reconnect is cancelled while it is running"
      def real = new EdgeRetryer(100, 0, 0, 10, 100, 100, 0)
      def seen = new java.util.concurrent.CompletableFuture<List<Boolean>>()
      def reconnector = {
        def before = real.isReconnectCancelled()
        real.cancelReconnects()
        seen.complete([before, real.isReconnectCancelled()])
      } as EdgeReconnector
    when:
      real.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, reconnector)
    then:
      seen.get(5, java.util.concurrent.TimeUnit.SECONDS) == [false, true]
      !real.isReconnectCancelled() // this thread isn't reconnecting
    cleanup:
      real.close()
  }

  def "closing while a reconnect is backing off doesn't reconnect, and we report being stopped"() {
    given:
      def real = EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().withServerConnectTimeoutMs(2000).build()
      def reconnector = Mock(EdgeReconnector)
      real.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, reconnector)
    when:
      real.close()
      real.executorService.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
    then:
      0 * reconnector.reconnect()
      real.stopped
  }
}
