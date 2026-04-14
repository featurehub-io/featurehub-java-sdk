package io.featurehub.client

import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PassivePollingDelegateEdgeServiceSpec extends Specification {
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

  // ---------------------------------------------------------------------------
  // First poll always proceeds (constructor sets whenLastPolled = now - 1 minute)
  // ---------------------------------------------------------------------------

  def "first poll always proceeds regardless of currentInterval"() {
    given:
      inner.isStopped() >> false
      inner.currentInterval() >> 30
      inner.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
      def service = new PassivePollingDelegateEdgeService(inner, repo)
    when:
      def result = service.poll().get(5, TimeUnit.SECONDS)
    then:
      result == Readiness.Ready
      1 * inner.poll()
  }

  // ---------------------------------------------------------------------------
  // Throttling: poll within interval returns readiness without calling inner
  // ---------------------------------------------------------------------------

  def "poll within currentInterval is throttled and returns readiness without delegating"() {
    given:
      inner.isStopped() >> false
      inner.currentInterval() >>> [30, 3600]  // 30s for first poll check, 3600s for second → throttled
      inner.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
      def service = new PassivePollingDelegateEdgeService(inner, repo)
      service.poll().get(5, TimeUnit.SECONDS)   // first poll — sets whenLastPolled to now
    when:
      def result = service.poll().get(5, TimeUnit.SECONDS)  // second poll — still within interval
    then:
      result == Readiness.Ready
      0 * inner.poll()  // throttled — inner not called during when:
  }

  // ---------------------------------------------------------------------------
  // After interval elapsed: poll delegates to inner again
  // ---------------------------------------------------------------------------

  def "poll after currentInterval has elapsed delegates to inner again"() {
    given:
      inner.isStopped() >> false
      inner.currentInterval() >> 1   // 1-second interval so we only need a short sleep
      inner.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
      def service = new PassivePollingDelegateEdgeService(inner, repo)
      service.poll().get(5, TimeUnit.SECONDS)   // first poll — sets whenLastPolled to now (given:, not counted)
      Thread.sleep(1100)                         // wait just over 1 second
    when:
      def result = service.poll().get(5, TimeUnit.SECONDS)
    then:
      result == Readiness.Ready
      1 * inner.poll()  // only the when: poll counts; the given: priming call is not counted
  }

  // ---------------------------------------------------------------------------
  // postPollActivity() updates whenLastPolled so a subsequent immediate poll is throttled
  // ---------------------------------------------------------------------------

  def "after a successful poll the next immediate poll is throttled by a large interval"() {
    given: "30s interval lets the first poll through (constructor: now-1min); 3600s throttles the second"
      inner.isStopped() >> false
      inner.currentInterval() >>> [30, 3600]
      inner.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
      def service = new PassivePollingDelegateEdgeService(inner, repo)
    when:
      def first  = service.poll().get(5, TimeUnit.SECONDS)   // proceeds — whenLastPolled + 30s < now
      def second = service.poll().get(5, TimeUnit.SECONDS)   // throttled — whenLastPolled + 3600s > now
    then:
      first  == Readiness.Ready
      second == Readiness.Ready
      1 * inner.poll()   // only the first call reaches inner
  }

  // ---------------------------------------------------------------------------
  // Stopped guard is inherited from base
  // ---------------------------------------------------------------------------

  def "poll returns current readiness immediately when inner service is stopped"() {
    given:
      inner.isStopped() >> true
      def service = new PassivePollingDelegateEdgeService(inner, repo)
    when:
      def result = service.poll().get(5, TimeUnit.SECONDS)
    then:
      result == Readiness.Ready
      0 * inner.poll()
  }
}
