package io.featurehub.client

import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ActivePollingDelegateEdgeServiceSpec extends Specification {
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
  private ActivePollingDelegateEdgeService serviceWithPreCancelledTimer() {
    return new ActivePollingDelegateEdgeService(inner, repo) {
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

  def "postPollActivity schedules the next poll at currentInterval seconds after a successful poll"() {
    given:
      def mockTimer = Mock(Timer)
      inner.isStopped() >> false
      inner.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
      inner.currentInterval() >> 60
      def service = new ActivePollingDelegateEdgeService(inner, repo) {
        @Override
        protected Timer newTimer() { return mockTimer }
      }
    when:
      service.poll().get()
    then:
      1 * mockTimer.schedule(_ as TimerTask, 60_000L)
  }

  def "postPollActivity does not schedule when the inner service is stopped"() {
    given:
      def mockTimer = Mock(Timer)
      inner.isStopped() >>> [false, true]  // false for the poll gate, true inside postPollActivity
      inner.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
      def service = new ActivePollingDelegateEdgeService(inner, repo) {
        @Override
        protected Timer newTimer() { return mockTimer }
      }
    when:
      service.poll().get()
    then:
      0 * mockTimer.schedule(_, _)
  }

  def "close() cancels the active timer to prevent further scheduled polls"() {
    given:
      def mockTimer = Mock(Timer)
      inner.isStopped() >> false
      def service = new ActivePollingDelegateEdgeService(inner, repo) {
        @Override
        protected Timer newTimer() { return mockTimer }
      }
    when:
      service.close()
    then:
      1 * mockTimer.cancel()
      1 * inner.close()
  }

  def "the timer fires and causes inner.poll() to be called a second time after the interval"() {
    given:
      def latch = new CountDownLatch(2)
      def pollCount = new AtomicInteger(0)
      inner.isStopped() >> { pollCount.get() >= 2 }
      inner.currentInterval() >> 0  // schedule immediately so the test doesn't wait
      inner.poll() >> {
        pollCount.incrementAndGet()
        latch.countDown()
        CompletableFuture.completedFuture(Readiness.Ready)
      }
      def service = new ActivePollingDelegateEdgeService(inner, repo)
    when:
      service.poll()
      latch.await(5, TimeUnit.SECONDS)
    then:
      latch.count == 0
      noExceptionThrown()
  }

}
