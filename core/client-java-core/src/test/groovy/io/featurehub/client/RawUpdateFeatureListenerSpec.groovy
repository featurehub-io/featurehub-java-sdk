package io.featurehub.client

import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import spock.lang.Specification

import java.util.concurrent.ExecutorService

class RawUpdateFeatureListenerSpec extends Specification {
  UUID envId
  ClientFeatureRepository repo
  RawUpdateFeatureListener listener

  def setup() {
    envId = UUID.randomUUID()
    ExecutorService exec = [
      execute    : { Runnable cmd -> cmd.run() },
      shutdownNow: { -> },
      isShutdown : { false }
    ] as ExecutorService
    repo = new ClientFeatureRepository(exec)
    listener = Mock(RawUpdateFeatureListener)
    repo.registerRawUpdateFeatureListener(listener)
  }

  FeatureState fs(String key) {
    new FeatureState().id(UUID.randomUUID()).environmentId(envId).version(1)
      .key(key).value(true).type(FeatureValueType.BOOLEAN)
  }

  def "updateFeatures notifies listener with the list and source"() {
    given:
      def features = [fs('a'), fs('b')]
    when:
      repo.updateFeatures(features, 'streaming')
    then:
      1 * listener.updateFeatures(features, 'streaming')
  }

  def "updateFeatures without source passes 'unknown' to listener"() {
    given:
      def features = [fs('a')]
    when:
      repo.updateFeatures(features)
    then:
      1 * listener.updateFeatures(features, 'unknown')
  }

  def "updateFeature notifies listener with the feature and source"() {
    given:
      def feature = fs('x')
    when:
      repo.updateFeature(feature, 'polling')
    then:
      1 * listener.updateFeature(feature, 'polling')
  }

  def "updateFeature without source passes 'unknown' to listener"() {
    given:
      def feature = fs('x')
    when:
      repo.updateFeature(feature)
    then:
      1 * listener.updateFeature(feature, 'unknown')
  }

  def "deleteFeature notifies listener with the feature and source, not updateFeature"() {
    given:
      def feature = fs('x')
    when:
      repo.deleteFeature(feature, 'streaming')
    then:
      1 * listener.deleteFeature(feature, 'streaming')
      0 * listener.updateFeature(_, _)
  }

  def "deleteFeature without source passes 'unknown' to listener"() {
    given:
      def feature = fs('x')
    when:
      repo.deleteFeature(feature)
    then:
      1 * listener.deleteFeature(feature, 'unknown')
      0 * listener.updateFeature(_, _)
  }

  def "updateFeatures does not trigger updateFeature on the listener"() {
    given:
      def features = [fs('a'), fs('b')]
    when:
      repo.updateFeatures(features, 'streaming')
    then:
      1 * listener.updateFeatures(features, 'streaming')
      0 * listener.updateFeature(_, _)
  }

  def "close() is called on all registered listeners when the repository is closed"() {
    given:
      def listener2 = Mock(RawUpdateFeatureListener)
      repo.registerRawUpdateFeatureListener(listener2)
    when:
      repo.close()
    then:
      1 * listener.close()
      1 * listener2.close()
  }

  def "multiple listeners all receive the same calls"() {
    given:
      def listener2 = Mock(RawUpdateFeatureListener)
      repo.registerRawUpdateFeatureListener(listener2)
      def features = [fs('a')]
    when:
      repo.updateFeatures(features, 'polling')
    then:
      1 * listener.updateFeatures(features, 'polling')
      1 * listener2.updateFeatures(features, 'polling')
  }

  def "registerRawUpdateFeatureListener on EdgeFeatureHubConfig delegates to the repository"() {
    given:
      def config = new EdgeFeatureHubConfig('http://localhost:8080/features', "${UUID.randomUUID()}/123*abc")
      ExecutorService syncExec = [execute: { Runnable cmd -> cmd.run() }, shutdownNow: { -> }, isShutdown: { false }] as ExecutorService
      def syncRepo = new ClientFeatureRepository(syncExec)
      config.setRepository(syncRepo)
      def cfgListener = Mock(RawUpdateFeatureListener)
      config.registerRawUpdateFeatureListener(cfgListener)
      def fs = fs('y')
    when:
      syncRepo.updateFeature(fs, 'streaming')
    then:
      1 * cfgListener.updateFeature(fs, 'streaming')
  }
}
