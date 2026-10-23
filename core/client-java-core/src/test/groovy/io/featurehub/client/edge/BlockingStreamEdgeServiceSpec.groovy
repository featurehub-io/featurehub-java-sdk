package io.featurehub.client.edge

import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.client.Readiness
import io.featurehub.sse.model.SSEResultState
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class BlockingStreamEdgeServiceSpec extends Specification {
  InternalFeatureRepository repository
  FeatureHubConfig config
  EdgeRetryService retryer
  List<Runnable> submitted
  List<Future> tasks
  ExecutorService executor
  Closure<BlockingStreamEdgeService.Stream> open

  def setup() {
    repository = Mock(InternalFeatureRepository)
    config = Mock(FeatureHubConfig)
    config.isServerEvaluation() >> true
    retryer = Mock(EdgeRetryService)
    submitted = []
    tasks = []
    executor = Mock(ExecutorService)
    executor.submit(_ as Runnable) >> { Runnable r ->
      submitted.add(r)
      def task = Mock(Future)
      tasks.add(task)
      task
    }
  }

  private BlockingStreamEdgeService service() {
    return new BlockingStreamEdgeService(repository, config, retryer) {
      @Override
      protected BlockingStreamEdgeService.Stream openStream(String header, String lastEventId) {
        return open.call(header, lastEventId)
      }

      @Override
      protected int statusOf(Throwable failure) {
        return failure.message?.isInteger() ? failure.message.toInteger() : 0
      }

      @Override
      protected ExecutorService connectExecutor() {
        return executor
      }
    }
  }

  private BlockingStreamEdgeService.Stream stream(List<BlockingStreamEdgeService.Event> events) {
    def closed = false
    return new BlockingStreamEdgeService.Stream() {
      boolean isClosed() { closed }

      BlockingStreamEdgeService.Event read() {
        if (events) {
          return events.remove(0)
        }
        closed = true
        return null
      }

      void close() { closed = true }
    }
  }

  def "events read from the stream are applied and complete a server evaluated context's wait"() {
    given:
      retryer.fromValue("features") >> SSEResultState.FEATURES
      open = { h, l -> stream([new BlockingStreamEdgeService.Event("features", "1", "data")]) }
      def edge = service()
    when:
      def future = edge.contextChange("userkey=fred", "1")
      submitted[0].run()
    then:
      1 * retryer.convertSSEState(SSEResultState.FEATURES, "data", repository, _)
      future.done
  }

  def "the status a failure carries decides whether we stop"() {
    given:
      open = { h, l -> throw new RuntimeException(status.toString()) }
      def edge = service()
    when:
      edge.contextChange("userkey=fred", "1")
      submitted[0].run()
    then:
      1 * retryer.edgeResult(state, edge)
    where:
      status | state
      404    | EdgeConnectionState.API_KEY_NOT_FOUND
      403    | EdgeConnectionState.FAILURE
      503    | EdgeConnectionState.CONNECTION_FAILURE
  }

  def "closing interrupts every connection task, including those of connections that were replaced"() {
    given: "connections for several contexts, still connecting"
      open = { h, l -> stream([]) }
      def edge = service()
      edge.contextChange("userkey=fred", "1")
      edge.contextChange("userkey=mary", "2")
      edge.contextChange("userkey=sue", "3")
    when:
      edge.close()
    then:
      tasks.size() == 3
      tasks.each { 1 * it.cancel(true) }
      1 * retryer.close()
  }

  def "finished connection tasks are forgotten"() {
    given:
      open = { h, l -> stream([]) }
      def edge = service()
      edge.contextChange("userkey=fred", "1")
      tasks[0].isDone() >> true
      edge.contextChange("userkey=mary", "2")
    when:
      edge.close()
    then:
      0 * tasks[0].cancel(_)
      1 * tasks[1].cancel(true)
  }

  def "a client evaluated context doesn't wait for features"() {
    given:
      def clientConfig = Mock(FeatureHubConfig)
      clientConfig.isServerEvaluation() >> false
      config = clientConfig
      repository.getReadiness() >> Readiness.NotReady
      open = { h, l -> stream([]) }
      def edge = service()
    when:
      def future = edge.contextChange("userkey=fred", "1")
    then:
      future.done
      submitted.size() == 1
  }

  def "closing several connections does not make them wait for each other"() {
    given: "connections whose close blocks, as it does while another thread is reading them"
      def started = new CountDownLatch(3)
      def release = new CountDownLatch(1)
      def streams = (1..3).collect {
        new BlockingStreamEdgeService.Stream() {
          boolean isClosed() { false }
          BlockingStreamEdgeService.Event read() { null }
          void close() { started.countDown(); release.await(10, TimeUnit.SECONDS) }
        }
      }
    when:
      streams.each { BlockingStreamEdgeService.closeInBackground(it) }
    then: "all of them start closing at once"
      started.await(5, TimeUnit.SECONDS)
    cleanup:
      release.countDown()
  }

  def "a read that returns nothing ends the stream, even if it says it is still open"() {
    given: "a stream that returns null without closing itself"
      def reads = 0
      open = { h, l ->
        new BlockingStreamEdgeService.Stream() {
          boolean isClosed() { false }
          BlockingStreamEdgeService.Event read() { reads++; null }
          void close() {}
        }
      }
      def edge = service()
    when:
      edge.contextChange("userkey=fred", "1")
      submitted[0].run()
    then: "it is read once and reported lost"
      reads == 1
      1 * retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, edge)
  }

  def "an event that stops the retryer closes the stream straight away and releases those waiting"() {
    given: "a stream that would send more after a failure event"
      def stopped = false
      retryer.isStopped() >> { stopped }
      retryer.fromValue("failure") >> SSEResultState.FAILURE
      retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, _) >> { stopped = true }
      def closed = false
      def reads = 0
      open = { h, l ->
        new BlockingStreamEdgeService.Stream() {
          boolean isClosed() { closed }
          BlockingStreamEdgeService.Event read() { reads++; new BlockingStreamEdgeService.Event("failure", null, null) }
          void close() { closed = true }
        }
      }
      def edge = service()
    when:
      def future = edge.contextChange("userkey=fred", "1")
      submitted[0].run()
    then:
      reads == 1
      closed
      future.done
  }

  def "once a connection is abandoned we report being stopped"() {
    given:
      open = { h, l -> throw new NoClassDefFoundError("wrong library") }
      def edge = service()
    when:
      edge.contextChange("userkey=fred", "1")
      submitted[0].run()
    then:
      edge.stopped
  }

  def "a connection task added just after we are closed cancels itself"() {
    given: "a connection claimed just before close"
      def lateTask = Mock(Future)
      executor = Mock(ExecutorService)
      executor.submit(_ as Runnable) >> lateTask
      def edge = service()
      def connection = edge.connections.claim(false)
      edge.close()
    when: "its task is added after close has cancelled the ones it found"
      edge.connect(connection)
    then:
      1 * lateTask.cancel(true)
  }
}
