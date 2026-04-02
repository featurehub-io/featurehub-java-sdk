package io.featurehub.sdk.yaml

import io.featurehub.client.FeatureHubConfig
import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType

import java.math.BigDecimal

class LocalYamlFeatureStoreSpec extends YamlSpecBase {
  FeatureHubConfig config = Mock()

  def setup() {
    config.getInternalRepository() >> internalRepo
  }

  def "does nothing when config is closed (getInternalRepository returns null)"() {
    given:
      config.getInternalRepository() >> null
    when:
      new LocalYamlFeatureStore(config, '/no/such/file.yaml')
    then:
      0 * internalRepo.updateFeatures(_, _)
  }

  def "does nothing when yaml file does not exist"() {
    when:
      new LocalYamlFeatureStore(config, '/no/such/file.yaml')
    then:
      0 * internalRepo.updateFeatures(_, _)
  }

  def "does nothing when flagValues map is missing from yaml"() {
    given:
      def f = yamlWith('empty.yaml', "someOtherKey: value\n")
    when:
      new LocalYamlFeatureStore(config, f.absolutePath)
    then:
      0 * internalRepo.updateFeatures(_, _)
  }

  def "loads boolean flag"() {
    given:
      def f = yamlWith('bool.yaml', "flagValues:\n  myFlag: true\n")
    when:
      new LocalYamlFeatureStore(config, f.absolutePath)
    then:
      1 * internalRepo.updateFeatures({ List<FeatureState> fs ->
        fs.size() == 1 &&
        fs[0].key == 'myFlag' &&
        fs[0].type == FeatureValueType.BOOLEAN &&
        fs[0].value == Boolean.TRUE &&
        fs[0].version == 1L &&
        fs[0].l == false
      }, 'local-yaml-store')
  }

  def "loads string flag"() {
    given:
      def f = yamlWith('str.yaml', "flagValues:\n  greeting: hello\n")
    when:
      new LocalYamlFeatureStore(config, f.absolutePath)
    then:
      1 * internalRepo.updateFeatures({ List<FeatureState> fs ->
        fs.size() == 1 &&
        fs[0].key == 'greeting' &&
        fs[0].type == FeatureValueType.STRING &&
        fs[0].value == 'hello'
      }, 'local-yaml-store')
  }

  def "loads number flags as BigDecimal"() {
    given:
      def f = yamlWith('num.yaml', "flagValues:\n  count: 42\n  ratio: 3.14\n")
    when:
      new LocalYamlFeatureStore(config, f.absolutePath)
    then:
      1 * internalRepo.updateFeatures({ List<FeatureState> fs ->
        def byKey = fs.collectEntries { [it.key, it] }
        byKey['count'].type == FeatureValueType.NUMBER &&
        byKey['count'].value == new BigDecimal('42') &&
        byKey['ratio'].type == FeatureValueType.NUMBER &&
        (byKey['ratio'].value as BigDecimal).compareTo(new BigDecimal('3.14')) == 0
      }, 'local-yaml-store')
  }

  def "string 'true'/'false' is detected as BOOLEAN"() {
    given:
      def f = yamlWith('bool-str.yaml', "flagValues:\n  t: 'true'\n  fa: 'false'\n")
    when:
      new LocalYamlFeatureStore(config, f.absolutePath)
    then:
      1 * internalRepo.updateFeatures({ List<FeatureState> fs ->
        def byKey = fs.collectEntries { [it.key, it] }
        byKey['t'].type == FeatureValueType.BOOLEAN &&
        byKey['t'].value == Boolean.TRUE &&
        byKey['fa'].type == FeatureValueType.BOOLEAN &&
        byKey['fa'].value == Boolean.FALSE
      }, 'local-yaml-store')
  }

  def "null value is detected as STRING with null value"() {
    given:
      def f = yamlWith('null.yaml', "flagValues:\n  k:\n")
    when:
      new LocalYamlFeatureStore(config, f.absolutePath)
    then:
      1 * internalRepo.updateFeatures({ List<FeatureState> fs ->
        fs[0].type == FeatureValueType.STRING &&
        fs[0].value == null
      }, 'local-yaml-store')
  }

  def "map value is detected as JSON and serialized"() {
    given:
      def f = yamlWith('json.yaml', "flagValues:\n  cfg:\n    x: 1\n    y: 2\n")
    when:
      new LocalYamlFeatureStore(config, f.absolutePath)
    then:
      1 * internalRepo.updateFeatures({ List<FeatureState> fs ->
        fs[0].type == FeatureValueType.JSON &&
        fs[0].value instanceof String
      }, 'local-yaml-store')
  }

  def "list value is detected as JSON and serialized"() {
    given:
      def f = yamlWith('list.yaml', "flagValues:\n  items:\n    - a\n    - b\n")
    when:
      new LocalYamlFeatureStore(config, f.absolutePath)
    then:
      1 * internalRepo.updateFeatures({ List<FeatureState> fs ->
        fs[0].type == FeatureValueType.JSON &&
        fs[0].value instanceof String
      }, 'local-yaml-store')
  }

  def "all features use the environmentId from the config"() {
    given:
      def envId = UUID.randomUUID()
      config.getEnvironmentId() >> envId
      def f = yamlWith('multi.yaml', "flagValues:\n  a: true\n  b: 'hello'\n  c: 42\n")
    when:
      new LocalYamlFeatureStore(config, f.absolutePath)
    then:
      1 * internalRepo.updateFeatures({ List<FeatureState> fs ->
        fs.size() == 3 &&
        fs.every { it.environmentId == envId }
      }, 'local-yaml-store')
  }

  def "keyToId produces deterministic UUID for the same key"() {
    expect:
      LocalYamlFeatureStore.keyToId('myFeature') == LocalYamlFeatureStore.keyToId('myFeature')
  }

  def "keyToId produces different UUIDs for different keys"() {
    expect:
      LocalYamlFeatureStore.keyToId('featureA') != LocalYamlFeatureStore.keyToId('featureB')
  }

  def "feature id is the deterministic UUID derived from the key"() {
    given:
      def f = yamlWith('id-check.yaml', "flagValues:\n  knownKey: true\n")
    when:
      new LocalYamlFeatureStore(config, f.absolutePath)
    then:
      1 * internalRepo.updateFeatures({ List<FeatureState> fs ->
        fs[0].id == LocalYamlFeatureStore.keyToId('knownKey')
      }, 'local-yaml-store')
  }

  def "uses default filename when none provided and file does not exist"() {
    when:
      new LocalYamlFeatureStore(config)
    then:
      0 * internalRepo.updateFeatures(_, _)
  }
}
