package io.featurehub.client

import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import spock.lang.Specification

class ExtendedInterceptorSpec extends Specification {
  UUID envId
  ClientFeatureRepository fr

  def setup() {
    envId = UUID.randomUUID()
    fr = new ClientFeatureRepository(1)
  }

  FeatureState fs(String key, Object value, FeatureValueType type) {
    return new FeatureState()
      .id(UUID.randomUUID())
      .environmentId(envId)
      .version(1)
      .key(key)
      .value(value)
      .type(type)
  }

  def "an extended interceptor returning a boolean directly overrides a false feature value"() {
    given: "a boolean feature set to false"
      fr.updateFeatures([fs('my_flag', false, FeatureValueType.BOOLEAN)])
    and: "an extended interceptor that returns true for that key"
      fr.registerValueInterceptor({ key, repo, rawFeature ->
        key == 'my_flag' ? new ExtendedFeatureValueInterceptor.ValueMatch(true, Boolean.TRUE) : null
      } as ExtendedFeatureValueInterceptor)
    expect:
      fr.getFeat('my_flag').flag
  }

  def "an extended interceptor returning a string overrides a feature value"() {
    given: "a string feature"
      fr.updateFeatures([fs('greeting', 'hello', FeatureValueType.STRING)])
    and: "an extended interceptor that returns an overridden string"
      fr.registerValueInterceptor({ key, repo, rawFeature ->
        key == 'greeting' ? new ExtendedFeatureValueInterceptor.ValueMatch(true, 'world') : null
      } as ExtendedFeatureValueInterceptor)
    expect:
      fr.getFeat('greeting').string == 'world'
  }

  def "an extended interceptor returning a number overrides a feature value"() {
    given: "a number feature"
      fr.updateFeatures([fs('count', 5, FeatureValueType.NUMBER)])
    and: "an extended interceptor that returns an overridden number"
      fr.registerValueInterceptor({ key, repo, rawFeature ->
        key == 'count' ? new ExtendedFeatureValueInterceptor.ValueMatch(true, new BigDecimal('99')) : null
      } as ExtendedFeatureValueInterceptor)
    expect:
      fr.getFeat('count').number == 99
  }

  def "an extended interceptor receives the repository and the raw feature state"() {
    given: "a feature"
      def featureState = fs('check_me', 'original', FeatureValueType.STRING)
      fr.updateFeatures([featureState])
    and: "a capturing interceptor"
      FeatureRepository capturedRepo = null
      FeatureState capturedState = null
      fr.registerValueInterceptor({ key, repo, rawFeature ->
        capturedRepo = repo
        capturedState = rawFeature
        null
      } as ExtendedFeatureValueInterceptor)
    when:
      fr.getFeat('check_me').string
    then:
      capturedRepo.is(fr)
      capturedState.key == 'check_me'
  }

  def "an extended interceptor receives null for the feature state when the key is not registered"() {
    given: "a capturing interceptor on an empty repository"
      boolean receivedNullState = false
      fr.registerValueInterceptor({ key, repo, rawFeature ->
        receivedNullState = (rawFeature == null)
        null
      } as ExtendedFeatureValueInterceptor)
    when:
      fr.getFeat('unknown').flag
    then:
      receivedNullState
  }

  def "when extended interceptor does not match, the actual feature value is returned"() {
    given: "a number feature"
      fr.updateFeatures([fs('count', 42, FeatureValueType.NUMBER)])
    and: "an extended interceptor that never matches"
      fr.registerValueInterceptor({ key, repo, rawFeature ->
        new ExtendedFeatureValueInterceptor.ValueMatch(false, null)
      } as ExtendedFeatureValueInterceptor)
    expect:
      fr.getFeat('count').number == 42
  }

  def "the first matching extended interceptor wins when multiple are registered"() {
    given: "a boolean feature set to false"
      fr.updateFeatures([fs('flag', false, FeatureValueType.BOOLEAN)])
    and: "two interceptors where the first returns true and the second returns false"
      fr.registerValueInterceptor({ key, repo, rawFeature ->
        new ExtendedFeatureValueInterceptor.ValueMatch(true, Boolean.TRUE)
      } as ExtendedFeatureValueInterceptor)
      fr.registerValueInterceptor({ key, repo, rawFeature ->
        new ExtendedFeatureValueInterceptor.ValueMatch(true, Boolean.FALSE)
      } as ExtendedFeatureValueInterceptor)
    expect:
      fr.getFeat('flag').flag
  }

  def "extended interceptor takes priority over an old-style interceptor"() {
    given: "a boolean feature set to false"
      fr.updateFeatures([fs('flag', false, FeatureValueType.BOOLEAN)])
    and: "an old-style interceptor that overrides to true"
      def oldInterceptor = Mock(FeatureValueInterceptor)
      oldInterceptor.getValue('flag') >> new FeatureValueInterceptor.ValueMatch(true, 'true')
      fr.registerValueInterceptor(true, oldInterceptor)
    and: "an extended interceptor that returns false"
      fr.registerValueInterceptor({ key, repo, rawFeature ->
        new ExtendedFeatureValueInterceptor.ValueMatch(true, Boolean.FALSE)
      } as ExtendedFeatureValueInterceptor)
    expect:
      !fr.getFeat('flag').flag
  }

  def "old-style interceptor is used as fallback when extended interceptor does not match"() {
    given: "a boolean feature set to false"
      fr.updateFeatures([fs('flag', false, FeatureValueType.BOOLEAN)])
    and: "an extended interceptor that does not match"
      fr.registerValueInterceptor({ key, repo, rawFeature -> null } as ExtendedFeatureValueInterceptor)
    and: "an old-style interceptor that overrides to true"
      def oldInterceptor = Mock(FeatureValueInterceptor)
      oldInterceptor.getValue('flag') >> new FeatureValueInterceptor.ValueMatch(true, 'true')
      fr.registerValueInterceptor(true, oldInterceptor)
    expect:
      fr.getFeat('flag').flag
  }

  def "a locked feature is still overridable by an extended interceptor"() {
    given: "a locked boolean feature set to false"
      fr.updateFeatures([fs('flag', false, FeatureValueType.BOOLEAN).l(true)])
    and: "an extended interceptor that returns true"
      fr.registerValueInterceptor({ key, repo, rawFeature ->
        new ExtendedFeatureValueInterceptor.ValueMatch(true, Boolean.TRUE)
      } as ExtendedFeatureValueInterceptor)
    expect:
      fr.getFeat('flag').flag
  }

  def "close() is called on all registered extended interceptors when the repository is closed"() {
    given: "two extended interceptors registered"
      def interceptor1 = Mock(ExtendedFeatureValueInterceptor)
      def interceptor2 = Mock(ExtendedFeatureValueInterceptor)
      fr.registerValueInterceptor(interceptor1)
      fr.registerValueInterceptor(interceptor2)
    when:
      fr.close()
    then:
      1 * interceptor1.close()
      1 * interceptor2.close()
  }

  def "registerValueInterceptor on EdgeFeatureHubConfig delegates to the repository"() {
    given: "a config"
      def config = new EdgeFeatureHubConfig('http://localhost:8080/features', "${UUID.randomUUID()}/123*abc")
    and: "an extended interceptor registered on the config"
      config.registerValueInterceptor({ key, repo, rawFeature ->
        key == 'demo' ? new ExtendedFeatureValueInterceptor.ValueMatch(true, Boolean.TRUE) : null
      } as ExtendedFeatureValueInterceptor)
    expect:
      config.repository.getFeat('demo').flag
  }
}