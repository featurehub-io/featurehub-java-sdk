package io.featurehub.client

import io.featurehub.client.interceptor.SystemPropertyValueInterceptor
import io.featurehub.sse.model.FeatureValueType
import io.featurehub.sse.model.FeatureState
import spock.lang.Specification

class InterceptorSpec extends Specification {
  def "a system property interceptor returns the correct overridden value"() {
    given: "we have a repository"
      def fr = new ClientFeatureRepository(1);
    and: "we set the system property value interceptor on it"
      fr.registerValueInterceptor(true, new SystemPropertyValueInterceptor())
    when: "we set the feature override"
      def featureName = "feature_one"
      def name = SystemPropertyValueInterceptor.FEATURE_TOGGLES_PREFIX + featureName
      System.setProperty(name, "true")
      System.setProperty(SystemPropertyValueInterceptor.FEATURE_TOGGLES_ALLOW_OVERRIDE, "true")
    then:
      fr.getFeat(featureName).flag
      fr.getFeat(featureName).string == 'true'
      fr.getFeat(featureName).number == null
      fr.getFeat("feature_none").string == null
      !fr.getFeat("feature_none").flag
  }

  def "we can deserialize json in an override"() {
    given: "we have a repository"
      def fr = new ClientFeatureRepository(1);
    and: "we set the system property value interceptor on it"
      fr.registerValueInterceptor(true, new SystemPropertyValueInterceptor())
    when: "we set the feature override"
      def featureName = 'feature_json'
      def name = SystemPropertyValueInterceptor.FEATURE_TOGGLES_PREFIX + "feature_json"
      def rawJson = '{"sample":18}'
      System.setProperty(name, rawJson)
      System.setProperty(SystemPropertyValueInterceptor.FEATURE_TOGGLES_ALLOW_OVERRIDE, "true")
    then:
      !fr.getFeat(featureName).flag
      fr.getFeat(featureName).string == rawJson
      fr.getFeat(featureName).rawJson == rawJson
      fr.getFeat(featureName).getJson(BananaSample) instanceof BananaSample
      fr.getFeat(featureName).getJson(BananaSample).sample == 18
      fr.getFeat("feature_none").getJson(BananaSample) == null
  }

  def "we can deserialize a number in an override"() {
    given: "we have a repository"
      def fr = new ClientFeatureRepository(1);
    and: "we set the system property value interceptor on it"
      fr.registerValueInterceptor(true, new SystemPropertyValueInterceptor())
    when: "we set the feature override"
      def featureName = 'feature_num'
      def name = SystemPropertyValueInterceptor.FEATURE_TOGGLES_PREFIX + featureName
      def numString = '17.65'
      System.setProperty(name, numString)
      System.setProperty(SystemPropertyValueInterceptor.FEATURE_TOGGLES_ALLOW_OVERRIDE, "true")
    then:
      !fr.getFeat(featureName).flag
      fr.getFeat(featureName).string == numString
      fr.getFeat(featureName).rawJson == numString
      fr.getFeat(featureName).number == 17.65
      fr.getFeat('feature_none').number == null
  }

  def "if system property loader is turned off, overrides are ignored"() {
    given: "we have a repository"
      def fr = new ClientFeatureRepository(1);
    and: "we set the system property value interceptor on it"
      fr.registerValueInterceptor(true, new SystemPropertyValueInterceptor())
    when: "we set the feature override"
      def name = SystemPropertyValueInterceptor.FEATURE_TOGGLES_PREFIX + "feature_one"
      System.setProperty(name, "true")
      System.clearProperty(SystemPropertyValueInterceptor.FEATURE_TOGGLES_ALLOW_OVERRIDE)
    then:
      !fr.getFeat("feature_one").flag
      fr.getFeat("feature_one").string == null
      fr.getFeat("feature_none").string == null
      !fr.getFeat("feature_none").flag
  }

  def "if a feature is locked, we won't call an interceptor that is overridden"() {
    given:
      def fr = new ClientFeatureRepository(1);
      fr.registerValueInterceptor(false, Mock(FeatureValueInterceptor))
    and: "we register a feature"
      fr.updateFeatures([new FeatureState().value(true).type(FeatureValueType.BOOLEAN).key("x").id(UUID.randomUUID()).l(true)])
    when: "i ask for the feature"
     def f = fr.getFeat("x").flag
    then:
      f
  }

  def "we can override registered feature values"() {
    given: "we have a repository"
      def fr = new ClientFeatureRepository(1);
    and: "we set the system property value interceptor on it"
      fr.registerValueInterceptor(true, new SystemPropertyValueInterceptor())
    and: "we have a set of features and register them"
      def banana = new FeatureState().id(UUID.randomUUID()).key('banana_or').version(1L).value(false).type(FeatureValueType.BOOLEAN)
      def orange = new FeatureState().id(UUID.randomUUID()).key('peach_or').version(1L).value("orange").type(FeatureValueType.STRING)
      def peachQuantity = new FeatureState().id(UUID.randomUUID()).key('peach-quantity_or').version(1L).value(17).type(FeatureValueType.NUMBER)
      def peachConfig = new FeatureState().id(UUID.randomUUID()).key('peach-config_or').version(1L).value("{}").type(FeatureValueType.JSON)
      def features = [banana, orange, peachConfig, peachQuantity]
      fr.updateFeatures(features)
    when: "we set the feature override"
      System.setProperty(SystemPropertyValueInterceptor.FEATURE_TOGGLES_PREFIX + banana.key, "true")
      System.setProperty(SystemPropertyValueInterceptor.FEATURE_TOGGLES_PREFIX + orange.key, "nectarine")
      System.setProperty(SystemPropertyValueInterceptor.FEATURE_TOGGLES_PREFIX + peachQuantity.key, "13")
      System.setProperty(SystemPropertyValueInterceptor.FEATURE_TOGGLES_PREFIX + peachConfig.key, '{"sample":12}')
      System.setProperty(SystemPropertyValueInterceptor.FEATURE_TOGGLES_ALLOW_OVERRIDE, "true")
    then:
      fr.getFeat(banana.key).flag
      fr.getFeat(orange.key).string == 'nectarine'
      fr.getFeat(peachQuantity.key).number == 13
      fr.getFeat(peachConfig.key).rawJson == '{"sample":12}'
      fr.getFeat(peachConfig.key).getJson(BananaSample).sample == 12

  }
}
