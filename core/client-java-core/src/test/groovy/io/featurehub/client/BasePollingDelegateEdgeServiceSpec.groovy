package io.featurehub.client

import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BasePollingDelegateEdgeServiceSpec extends Specification {
  EdgeService inner
  InternalFeatureRepository repo
  ExecutorService executor
  BasePollingDelegateEdgeService service

  def setup() {
    inner = Mock(EdgeService)
    repo = Mock(InternalFeatureRepository)
    executor = Executors.newSingleThreadExecutor()
    repo.getExecutor() >> executor
    repo.getReadiness() >> Readiness.Ready
    service = new BasePollingDelegateEdgeService(inner, repo) {}
  }

  def cleanup() {
    executor.shutdownNow()
  }

  // ---------------------------------------------------------------------------
  // poll()
  // ---------------------------------------------------------------------------

  def "poll() returns current readiness immediately when inner service is stopped"() {
    given:
      inner.isStopped() >> true
    when:
      def result = service.poll().get()
    then:
      result == Readiness.Ready
      0 * inner.poll()
  }

  def "poll() delegates to inner service and returns the result"() {
    given:
      inner.isStopped() >> false
    when:
      def result = service.poll().get()
    then:
      1 * inner.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
      result == Readiness.Ready
  }

  def "poll() returns repo readiness when inner service poll throws"() {
    given:
      inner.isStopped() >> false
      inner.poll() >> { throw new RuntimeException("network error") }
    when:
      def result = service.poll().get()
    then:
      result == Readiness.Ready
  }

  def "a second poll() while busy is queued and completed when the first poll finishes"() {
    given:
      def pollStarted = new CountDownLatch(1)
      def releasePoll = new CountDownLatch(1)
      inner.isStopped() >> false
      inner.poll() >> {
        pollStarted.countDown()
        releasePoll.await()
        CompletableFuture.completedFuture(Readiness.Ready)
      }
    when:
      def future1 = service.poll()
      pollStarted.await(5, TimeUnit.SECONDS)  // wait until first poll is in progress
      def future2 = service.poll()            // busy=true → queued in waitingClients
      releasePoll.countDown()                 // unblock first poll
    then:
      future1.get(5, TimeUnit.SECONDS) == Readiness.Ready
      future2.get(5, TimeUnit.SECONDS) == Readiness.Ready
  }

  def "postPollActivity() resets the busy flag so a subsequent poll proceeds normally"() {
    given:
      inner.isStopped() >> false
      inner.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
    when:
      service.poll().get()
      def result = service.poll().get(5, TimeUnit.SECONDS)
    then:
      result == Readiness.Ready
  }

  // ---------------------------------------------------------------------------
  // contextChange()
  // ---------------------------------------------------------------------------

  def "contextChange() returns current readiness immediately when inner is stopped"() {
    given:
      inner.isStopped() >> true
    when:
      def result = service.contextChange('header', 'sha').get()
    then:
      result == Readiness.Ready
      0 * inner.contextChange(_, _)
  }

  def "contextChange() returns current readiness immediately when needsContextChange is false"() {
    given:
      inner.isStopped() >> false
      inner.needsContextChange('header', 'sha') >> false
    when:
      def result = service.contextChange('header', 'sha').get()
    then:
      result == Readiness.Ready
      0 * inner.contextChange(_, _)
  }

  def "contextChange() delegates to inner service and returns the result"() {
    given:
      inner.isStopped() >> false
      inner.needsContextChange('header', 'sha') >> true
    when:
      def result = service.contextChange('header', 'sha').get()
    then:
      1 * inner.contextChange('header', 'sha') >> CompletableFuture.completedFuture(Readiness.Ready)
      result == Readiness.Ready
  }

  def "contextChange() returns repo readiness when inner contextChange throws"() {
    given:
      inner.isStopped() >> false
      inner.needsContextChange(_, _) >> true
      inner.contextChange(_, _) >> { throw new RuntimeException("network error") }
    when:
      def result = service.contextChange('header', 'sha').get()
    then:
      result == Readiness.Ready
  }

  // ---------------------------------------------------------------------------
  // Pure delegates
  // ---------------------------------------------------------------------------

  def "isClientEvaluation() delegates to inner service"() {
    when:
      def result = service.isClientEvaluation()
    then:
      1 * inner.isClientEvaluation() >> true
      result == true
  }

  def "isStopped() delegates to inner service"() {
    when:
      def result = service.isStopped()
    then:
      1 * inner.isStopped() >> false
      result == false
  }

  def "close() delegates to inner when inner is not already stopped"() {
    given:
      inner.isStopped() >> false
    when:
      service.close()
    then:
      1 * inner.close()
  }

  def "close() does not call inner close when inner is already stopped"() {
    given:
      inner.isStopped() >> true
    when:
      service.close()
    then:
      0 * inner.close()
  }

  def "getConfig() delegates to inner service"() {
    given:
      def config = Mock(FeatureHubConfig)
    when:
      def result = service.getConfig()
    then:
      1 * inner.getConfig() >> config
      result == config
  }

  def "currentInterval() delegates to inner service"() {
    when:
      def result = service.currentInterval()
    then:
      1 * inner.currentInterval() >> 30L
      result == 30L
  }
}
