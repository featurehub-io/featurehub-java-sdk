package io.featurehub.client

import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PollingDelegateEdgeServiceSpec extends Specification {
  EdgeService inner
  InternalFeatureRepository repo
  ExecutorService executor

  def setup() {
    inner = Mock(EdgeService)
    repo = Mock(InternalFeatureRepository)
    executor = Executors.newSingleThreadExecutor()
    repo.getExecutor() >> executor
    repo.getReadiness() >> Readiness.Ready
  }

  def cleanup() {
    executor.shutdownNow()
  }

  // Returns a PollingDelegateEdgeService whose newTimer() always yields a pre-cancelled timer,
  // reproducing the race where close()/contextChange() cancels the timer between newTimer() and schedule().
  private PollingDelegateEdgeService serviceWithPreCancelledTimer() {
    return new PollingDelegateEdgeService(inner, repo) {
      @Override
      protected Timer newTimer() {
        Timer t = super.newTimer()
        t.cancel()
        return t
      }
    }
  }

  def "poll() future completes normally when the timer is cancelled concurrently inside loop()"() {
    given:
      inner.isStopped() >> false
      inner.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
      inner.currentInterval() >> 60
      def service = serviceWithPreCancelledTimer()

    when:
      Readiness result = service.poll().get()

    then: "future resolves normally — no ExecutionException wrapping IllegalStateException"
      result == Readiness.Ready
      noExceptionThrown()
      0 * inner.close()
  }

  def "contextChange() future completes normally when the timer is cancelled concurrently inside loop()"() {
    given:
      inner.isStopped() >> false
      inner.needsContextChange("userkey=fred", _ as String) >> true
      inner.contextChange("userkey=fred", _ as String) >> CompletableFuture.completedFuture(Readiness.Ready)
      inner.currentInterval() >> 60
      def service = serviceWithPreCancelledTimer()

    when:
      Readiness result = service.contextChange("userkey=fred", "abc123").get()

    then: "future resolves normally — no ExecutionException wrapping IllegalStateException"
      result == Readiness.Ready
      noExceptionThrown()
      0 * inner.close()
  }

  def "poll() is a no-op and returns current readiness when already stopped"() {
    given:
      inner.isStopped() >> true
      def service = new PollingDelegateEdgeService(inner, repo)

    when:
      service.close()
      Readiness result = service.poll().get()

    then:
      result == Readiness.Ready
      1 * inner.close()
      0 * inner.poll()
  }
}
