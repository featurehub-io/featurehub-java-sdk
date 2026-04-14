package io.featurehub.sdk.yaml

import io.featurehub.client.ExtendedFeatureValueInterceptor
import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType


class LocalYamlValueInterceptorSpec extends YamlSpecBase {
  FeatureState featureState = Mock()

  String testYaml() {
    getClass().getResource('/test-features.yaml').getFile()
  }

  LocalYamlValueInterceptor interceptor(String filename, boolean watch = false) {
    new LocalYamlValueInterceptor(config, filename, watch)
  }

  ExtendedFeatureValueInterceptor.ValueMatch match(LocalYamlValueInterceptor i, String key) {
    i.getValue(key, internalRepo, featureState)
  }

  ExtendedFeatureValueInterceptor.ValueMatch matchTyped(LocalYamlValueInterceptor i, String key, FeatureValueType type) {
    def fs = Mock(FeatureState)
    fs.getType() >> type
    i.getValue(key, internalRepo, fs)
  }

  def "returns null for a missing key"() {
    expect:
      match(interceptor(testYaml()), 'nonexistent') == null
  }

  def "returns null when the yaml file does not exist"() {
    expect:
      match(interceptor('/no/such/file.yaml'), 'boolTrue') == null
  }

  def "reads boolean true value"() {
    when:
      def result = match(interceptor(testYaml()), 'boolTrue')
    then:
      result.matched
      result.value == Boolean.TRUE
      result.valueIsOriginal
  }

  def "reads boolean false value"() {
    when:
      def result = match(interceptor(testYaml()), 'boolFalse')
    then:
      result.matched
      result.value == Boolean.FALSE
  }

  def "reads string value"() {
    when:
      def result = match(interceptor(testYaml()), 'myString')
    then:
      result.matched
      result.value == 'hello world'
  }

  def "reads integer as BigDecimal"() {
    when:
      def result = match(interceptor(testYaml()), 'myNumber')
    then:
      result.matched
      result.value instanceof BigDecimal
      result.value == new BigDecimal('42')
  }

  def "reads float as BigDecimal"() {
    when:
      def result = match(interceptor(testYaml()), 'myFloat')
    then:
      result.matched
      result.value instanceof BigDecimal
      (result.value as BigDecimal).compareTo(new BigDecimal('3.14')) == 0
  }

  def "converts complex map to JSON string via the internalRepository mapper"() {
    when:
      def result = match(interceptor(testYaml()), 'myJson')
    then:
      1 * jsonMapper.writeValueAsString({ it instanceof Map }) >> '{"colour":"red","count":"5"}'
      result.matched
      result.value instanceof String
      (result.value as String).contains('"colour"')
      (result.value as String).contains('"red"')
  }

  def "converts list to JSON string via the internalRepository mapper"() {
    when:
      def result = match(interceptor(testYaml()), 'myJsonList')
    then:
      1 * jsonMapper.writeValueAsString({ it instanceof List }) >> '["a","b"]'
      result.matched
      result.value instanceof String
      (result.value as String).contains('"a"')
      (result.value as String).contains('"b"')
  }

  // --- Type-aware resolution tests ---

  def "BOOLEAN type: null yaml value returns false"() {
    given:
      def f = tempDir.resolve('bool-null.yaml').toFile()
      f.text = "flagValues:\n  k:\n"
      def i = interceptor(f.absolutePath)
    expect:
      matchTyped(i, 'k', FeatureValueType.BOOLEAN).value == Boolean.FALSE
  }

  def "BOOLEAN type: boolean values pass through"() {
    given:
      def f = tempDir.resolve('bool-vals.yaml').toFile()
      f.text = "flagValues:\n  t: true\n  fa: false\n"
      def i = interceptor(f.absolutePath)
    expect:
      matchTyped(i, 't', FeatureValueType.BOOLEAN).value == Boolean.TRUE
      matchTyped(i, 'fa', FeatureValueType.BOOLEAN).value == Boolean.FALSE
  }

  def "BOOLEAN type: string 'true' returns true, other strings return false"() {
    given:
      def f = tempDir.resolve('bool-str.yaml').toFile()
      f.text = "flagValues:\n  trueStr: 'true'\n  otherStr: 'nope'\n"
      def i = interceptor(f.absolutePath)
    expect:
      matchTyped(i, 'trueStr', FeatureValueType.BOOLEAN).value == Boolean.TRUE
      matchTyped(i, 'otherStr', FeatureValueType.BOOLEAN).value == Boolean.FALSE
  }

  def "NUMBER type: integer and float return BigDecimal"() {
    given:
      def f = tempDir.resolve('num-vals.yaml').toFile()
      f.text = "flagValues:\n  n: 42\n  d: 3.14\n"
      def i = interceptor(f.absolutePath)
    expect:
      matchTyped(i, 'n', FeatureValueType.NUMBER).value == new BigDecimal('42')
      (matchTyped(i, 'd', FeatureValueType.NUMBER).value as BigDecimal).compareTo(new BigDecimal('3.14')) == 0
  }

  def "NUMBER type: numeric string converts to BigDecimal, non-numeric string returns null"() {
    given:
      def f = tempDir.resolve('num-str.yaml').toFile()
      f.text = "flagValues:\n  good: '99.9'\n  bad: notanumber\n"
      def i = interceptor(f.absolutePath)
    expect:
      (matchTyped(i, 'good', FeatureValueType.NUMBER).value as BigDecimal).compareTo(new BigDecimal('99.9')) == 0
      matchTyped(i, 'bad', FeatureValueType.NUMBER).value == null
  }

  def "NUMBER type: null value returns null"() {
    given:
      def f = tempDir.resolve('num-null.yaml').toFile()
      f.text = "flagValues:\n  k:\n"
      def i = interceptor(f.absolutePath)
    expect:
      matchTyped(i, 'k', FeatureValueType.NUMBER).value == null
  }

  def "STRING type: string, number and boolean are coerced to string"() {
    given:
      def f = tempDir.resolve('str-vals.yaml').toFile()
      f.text = "flagValues:\n  s: hello\n  n: 7\n  b: true\n"
      def i = interceptor(f.absolutePath)
    expect:
      matchTyped(i, 's', FeatureValueType.STRING).value == 'hello'
      matchTyped(i, 'n', FeatureValueType.STRING).value == '7'
      matchTyped(i, 'b', FeatureValueType.STRING).value == 'true'
  }

  def "STRING type: map value returns null"() {
    given:
      def f = tempDir.resolve('str-map.yaml').toFile()
      f.text = "flagValues:\n  m:\n    k: v\n"
      def i = interceptor(f.absolutePath)
    expect:
      matchTyped(i, 'm', FeatureValueType.STRING).value == null
  }

  def "JSON type: map and list are serialized via internalRepository mapper"() {
    given:
      def f = tempDir.resolve('json-objs.yaml').toFile()
      f.text = "flagValues:\n  obj:\n    x: 1\n  arr:\n    - p\n    - q\n"
      def i = interceptor(f.absolutePath)
    when:
      def mapResult = matchTyped(i, 'obj', FeatureValueType.JSON)
      def listResult = matchTyped(i, 'arr', FeatureValueType.JSON)
    then:
      1 * jsonMapper.writeValueAsString({ it instanceof Map }) >> '{"x":"1"}'
      1 * jsonMapper.writeValueAsString({ it instanceof List }) >> '["p","q"]'
      mapResult.value == '{"x":"1"}'
      listResult.value == '["p","q"]'
  }

  def "JSON type: string value is passed through internalRepository mapper"() {
    given:
      def f = tempDir.resolve('json-str.yaml').toFile()
      f.text = "flagValues:\n  s: hello\n"
      def i = interceptor(f.absolutePath)
    when:
      def result = matchTyped(i, 's', FeatureValueType.JSON)
    then:
      1 * jsonMapper.readMapValue('hello') >> [] // can be anything, just not null
      1 * jsonMapper.writeValueAsString('hello') >> '"hello"'
      result.value == '"hello"'
  }

  // --- End type-aware tests ---

  def "defaults to featurehub-features.yaml when no filename given and env var not set"() {
    given:
      def i = new LocalYamlValueInterceptor(config)
    expect:
      match(i, 'anything') == null
  }

  def "loads from an explicit filename path"() {
    given:
      def yamlFile = tempDir.resolve('override.yaml').toFile()
      yamlFile.text = "flagValues:\n  envFlag: true\n"
    when:
      def result = match(interceptor(yamlFile.absolutePath), 'envFlag')
    then:
      result.matched
      result.value == Boolean.TRUE
  }

  def "reloads values when loadFile is called again after file changes"() {
    given:
      def yamlFile = tempDir.resolve('reload.yaml').toFile()
      yamlFile.text = "flagValues:\n  reloadMe: false\n"
      def i = interceptor(yamlFile.absolutePath)
    expect:
      match(i, 'reloadMe').value == Boolean.FALSE
    when:
      yamlFile.text = "flagValues:\n  reloadMe: true\n"
      i.loadFile()
    then:
      match(i, 'reloadMe').value == Boolean.TRUE
  }

  def "watches for file changes and reloads automatically"() {
    given:
      def yamlFile = tempDir.resolve('watched.yaml').toFile()
      yamlFile.text = "flagValues:\n  watchedFlag: false\n"
      def i = interceptor(yamlFile.absolutePath, true)
      // macOS WatchService polls at 2–10 s intervals, so use a generous timeout
      def watchConditions = new spock.util.concurrent.PollingConditions(timeout: 15, initialDelay: 0.5, delay: 0.5)
    expect:
      match(i, 'watchedFlag').value == Boolean.FALSE
    when:
      yamlFile.text = "flagValues:\n  watchedFlag: true\n"
    then:
      watchConditions.eventually {
        assert match(i, 'watchedFlag').value == Boolean.TRUE
      }
    cleanup:
      i.close()
  }

  def "close() stops the watch thread and nulls the watch state"() {
    given:
      def yamlFile = tempDir.resolve('close-test.yaml').toFile()
      yamlFile.text = "flagValues:\n  x: true\n"
      def i = interceptor(yamlFile.absolutePath, true)
    when:
      i.close()
    then:
      i.watchThread == null
      i.watchService == null
  }

  def "close() is safe to call when not watching"() {
    when:
      interceptor(testYaml(), false).close()
    then:
      noExceptionThrown()
  }

}
