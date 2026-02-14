package io.featurehub.client

import com.fasterxml.jackson.databind.ObjectMapper
import io.featurehub.javascript.Jackson2ObjectMapper
import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import io.featurehub.sse.model.SSEResultState
import spock.lang.Specification

import java.util.concurrent.ExecutorService
import java.util.function.Consumer

enum Fruit implements Feature { banana, peach, peach_quantity, peach_config, dragonfruit }

class RepositorySpec extends Specification {
  ClientFeatureRepository repo
  ExecutorService exec

  def setup() {
    exec = [
      execute: { Runnable cmd -> cmd.run() },
      shutdownNow: { -> },
      isShutdown: { false }
    ] as ExecutorService

    repo = new ClientFeatureRepository(exec)
  }

  def "an empty repository is not ready"() {
    when: "ask for the readyness status"
      def ready = repo.readyness
    then:
      ready == Readiness.NotReady
  }

  def "a set of features should trigger readyness and make all features available"() {
    given: "we have features"
      def features = [
        new FeatureState().id(UUID.randomUUID()).key('banana').version(1L).value(false).type(FeatureValueType.BOOLEAN),
        new FeatureState().id(UUID.randomUUID()).key('peach').version(1L).value("orange").type(FeatureValueType.STRING).featureProperties(Map.of("pork", "dumplings")),
        new FeatureState().id(UUID.randomUUID()).key('peach_quantity').version(1L).value(17).type(FeatureValueType.NUMBER),
        new FeatureState().id(UUID.randomUUID()).key('peach_config').version(1L).value("{}").type(FeatureValueType.JSON),
      ]
    and: "we have a readyness listener"
      Consumer<Readiness> readinessHandler = Mock(Consumer<Readiness>)
    when: // have to do this in the when or it isn't tracking the mock
      repo.addReadinessListener(readinessHandler)
    and:
      repo.updateFeatures(features)
    then:
      1 * readinessHandler.accept(Readiness.Ready)
      1 * readinessHandler.accept(Readiness.NotReady)
      0 * _
      !repo.getFeat('banana').flag
      repo.getFeat('banana').key == 'banana'
      repo.getFeat('banana').exists()
      repo.getFeat('banana').featureProperties().isEmpty()
      repo.getFeat(Fruit.banana).exists()
      !repo.getFeat('dragonfruit').exists()
      !repo.getFeat(Fruit.dragonfruit).exists()
      repo.getFeat('banana').rawJson == null
      repo.getFeat('banana').string == null
      repo.getFeat('banana').number == null
      repo.getFeat('banana').number == null
      repo.getFeat('banana').set
      !repo.getFeat('banana').enabled
      repo.getFeat('peach').string == 'orange'
      repo.getFeat('peach').exists()
      repo.getFeat('peach').featureProperties() == ['pork': 'dumplings']
      repo.getFeat(Fruit.peach).exists()
      repo.getFeat('peach').key == 'peach'
      repo.getFeat('peach').number == null
      repo.getFeat('peach').rawJson == null
      repo.getFeat('peach').flag == null
      repo.getFeat('peach_quantity').number == 17
      repo.getFeat('peach_quantity').featureProperties().isEmpty()
      repo.getFeat('peach_quantity').rawJson == null
      repo.getFeat('peach_quantity').flag == null
      repo.getFeat('peach_quantity').string == null
      repo.getFeat('peach_quantity').key == 'peach_quantity'
      repo.getFeat('peach_config').rawJson == '{}'
      repo.getFeat('peach_config').string == null
      repo.getFeat('peach_config').number == null
      repo.getFeat('peach_config').flag == null
      repo.getFeat('peach_config').key == 'peach_config'
      repo.getFeat('peach_config').featureProperties().isEmpty()
      repo.getAllFeatures().size() == 5
  }

  def "i can make all features available directly"() {
    given: "we have features"
      def features = [
        new FeatureState().id(UUID.randomUUID()).key('banana').version(1L).value(false).type(FeatureValueType.BOOLEAN),
      ]
    when:
      repo.updateFeatures(features)
      def feature = repo.getFeat('banana').flag
    and: "i make a change to the state but keep the version the same (ok because this is what rollout strategies do)"
      repo.updateFeatures([
        new FeatureState().id(UUID.randomUUID()).key('banana').version(1L).value(true).type(FeatureValueType.BOOLEAN),
      ])
      def feature2 = repo.getFeat('banana').flag
    and: "then i make the change but up the version"
      repo.updateFeatures([
        new FeatureState().id(UUID.randomUUID()).key('banana').version(2L).value(true).type(FeatureValueType.BOOLEAN),
      ])
      def feature3 = repo.getFeat('banana').flag
    and: "then i make a change but force it even if the version is the same"
      repo.updateFeatures([
        new FeatureState().id(UUID.randomUUID()).key('banana').version(1L).value(false).type(FeatureValueType.BOOLEAN),
      ], true)
      def feature4 = repo.getFeat('banana').flag
    then:
      !feature
      feature2
      feature3
      !feature4
  }

  def "a non existent feature is not set"() {
    when: "we ask for a feature that doesn't exist"
      def feature = repo.getFeat('fred')
    then:
      !feature.enabled
  }

  def "a feature is deleted that doesn't exist and thats ok"() {
    when: "i create a feature to delete"
      def feature = new FeatureState().id(UUID.randomUUID()).key('banana').version(1L).value(true).type(FeatureValueType.BOOLEAN)
    and: "i delete a non existent feature"
      repo.deleteFeature(feature)
    then:
      !repo.getFeat('banana').enabled
  }

  def "A feature is deleted and it is now not set"() {
    given: "i have a feature"
      def feature = new FeatureState().id(UUID.randomUUID()).key('banana').version(1L).value(true).type(FeatureValueType.BOOLEAN)
    and: "i notify repo"
      repo.updateFeatures([feature])
    when: "i check the feature state"
      def f = repo.getFeat('banana').flag
    and: "i delete the feature"
      def featureDel = new FeatureState().id(UUID.randomUUID()).key('banana').version(2L).value(true).type(FeatureValueType.BOOLEAN)
      repo.deleteFeature(featureDel)
    then:
      f
      !repo.getFeat('banana').enabled
  }


  def "a json config will properly deserialize into an object"() {
    given: "i have features"
      def features = [
        new FeatureState().id(UUID.randomUUID()).key('banana').version(1L).value('{"sample":12}').type(FeatureValueType.JSON),
      ]
    and: "i register an alternate object mapper"
      repo.setJsonConfigObjectMapper(new Jackson2ObjectMapper())
    when: "i notify of features"
      repo.updateFeatures(features)
    then: 'the json object is there and deserialises'
      repo.getFeat('banana').getJson(BananaSample) instanceof BananaSample
      repo.getFeat(Fruit.banana).getJson(BananaSample) instanceof BananaSample
      repo.getFeat('banana').getJson(BananaSample).sample == 12
      repo.getFeat(Fruit.banana).getJson(BananaSample).sample == 12
  }

  def "failure changes readiness to failure"() {
    given: "i have features"
      def features = [
        new FeatureState().id(UUID.randomUUID()).key('banana').version(1L).value(false).type(FeatureValueType.BOOLEAN),
      ]
    and: "i notify the repo"
      List<Readiness> statuses = []
      Consumer<Readiness> readynessHandler = { Readiness r ->
        print("called $r")
        statuses.add(r)
      }
      repo.addReadinessListener(readynessHandler)
      repo.updateFeatures(features)
      def readyness = repo.readyness
    when: "i indicate failure"
      repo.notify(SSEResultState.FAILURE)
    then: "we swap to not ready"
      repo.readiness == Readiness.Failed
      readyness == Readiness.Ready
      statuses == [Readiness.NotReady, Readiness.Ready, Readiness.Failed]
  }

  def "ack and bye are ignored"() {
    given: "i have features"
      def features = [
        new FeatureState().id(UUID.randomUUID()).key('banana').version(1L).value(false).type(FeatureValueType.BOOLEAN),
      ]
    and: "i notify the repo"
      repo.updateFeatures(features)
    when: "i ack and then bye, nothing happens"
      repo.notify(SSEResultState.ACK)
      repo.notify(SSEResultState.BYE)
    then:
      repo.readyness == Readiness.Ready
  }

  def "i can attach to a feature before it is added and receive notifications when it is"() {
    given: "i have one of each feature type"
      def features = [
        new FeatureState().id(UUID.randomUUID()).key('banana').version(1L).value(false).type(FeatureValueType.BOOLEAN),
        new FeatureState().id(UUID.randomUUID()).key('peach').version(1L).value("orange").type(FeatureValueType.STRING),
        new FeatureState().id(UUID.randomUUID()).key('peach_quantity').version(1L).value(17).type(FeatureValueType.NUMBER),
        new FeatureState().id(UUID.randomUUID()).key('peach_config').version(1L).value("{}").type(FeatureValueType.JSON),
      ]
    and: "I listen for updates for those features"
      def updateListener = []
      List<io.featurehub.client.FeatureState> emptyFeatures = []
      features.each {f ->
        def feature = repo.getFeat(f.key)
        def listener = Mock(FeatureListener)
        updateListener.add(listener)
        feature.addListener(listener)
        emptyFeatures.add(feature.usageCopy())
      }
      def featureCountAfterRequestingEmptyFeatures = repo.allFeatures.size()
    when: "i fill in the repo"
      repo.updateFeatures(features)
    then:
      featureCountAfterRequestingEmptyFeatures == features.size()
      updateListener.each {
        1 * it.notify(_)
      }
      emptyFeatures.each {f ->
        f.key != null
        !f.enabled
        f.string == null
        f.flag == null
        f.rawJson == null
        f.number == null
      }
    features.each { it ->
      repo.getFeat(it.key).key == it.key
      repo.getFeat(it.key).enabled

      if (it.type == FeatureValueType.BOOLEAN)
        repo.getFeat(it.key).flag == it.value
      else
        repo.getFeat(it.key).flag == null

      if (it.type == FeatureValueType.NUMBER)
        repo.getFeat(it.key).number == it.value
      else
        repo.getFeat(it.key).number == null

      if (it.type == FeatureValueType.STRING)
        repo.getFeat(it.key).string.equals(it.value)
      else
        repo.getFeat(it.key).string == null

      if (it.type == FeatureValueType.JSON)
        repo.getFeat(it.key).rawJson.equals(it.value)
      else
        repo.getFeat(it.key).rawJson == null
    }

  }

}
