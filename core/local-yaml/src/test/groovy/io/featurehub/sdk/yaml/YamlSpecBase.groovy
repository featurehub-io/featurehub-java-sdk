package io.featurehub.sdk.yaml

import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.javascript.JavascriptObjectMapper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Base spec providing shared mocks, JSON mapper stub, and YAML file helpers
 * for local-yaml module tests.
 */
abstract class YamlSpecBase extends Specification {
  @TempDir Path tempDir

  FeatureHubConfig config = Mock()
  InternalFeatureRepository internalRepo = Mock()
  JavascriptObjectMapper jsonMapper = Mock()

  def setup() {
    config.getInternalRepository() >> internalRepo
    internalRepo.getJsonObjectMapper() >> jsonMapper
    jsonMapper.writeValueAsString(_) >> { args ->
      def obj = args[0]
      if (obj instanceof Map) {
        def entries = obj.collect { k, v -> "\"${k}\":\"${v}\"" }.join(',')
        return "{${entries}}"
      } else if (obj instanceof List) {
        def items = obj.collect { "\"${it}\"" }.join(',')
        return "[${items}]"
      }
      return "\"${obj}\""
    }
  }

  /**
   * Writes {@code content} to a file named {@code name} in the temp directory
   * and returns the File.
   */
  File yamlWith(String name, String content) {
    def f = tempDir.resolve(name).toFile()
    f.text = content
    f
  }
}
