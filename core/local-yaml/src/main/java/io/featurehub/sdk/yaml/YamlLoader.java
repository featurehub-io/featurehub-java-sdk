package io.featurehub.sdk.yaml;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Shared utility for loading the {@code flagValues} map from a YAML override file.
 */
class YamlLoader {
  private YamlLoader() {}

  /**
   * Reads the file at {@code yamlFile} and returns the contents of the top-level
   * {@code flagValues} map. Returns an empty map if the file does not exist, is
   * empty, or does not contain a {@code flagValues} key.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> readFlagValues(File yamlFile, Logger log) {
    if (!yamlFile.exists()) {
      log.debug("YAML override file {} not found, no overrides applied", yamlFile.getAbsolutePath());
      return Collections.emptyMap();
    }

    try (FileInputStream fis = new FileInputStream(yamlFile)) {
      Map<String, Object> data = new Yaml().load(fis);

      if (data != null && data.get("flagValues") instanceof Map) {
        Map<String, Object> values = (Map<String, Object>) data.get("flagValues");
        log.debug("Loaded {} feature value(s) from {}", values.size(), yamlFile.getName());
        return values;
      } else {
        log.debug("No flagValues map found in {}", yamlFile.getName());
        return Collections.emptyMap();
      }
    } catch (IOException e) {
      log.error("Failed to load YAML file {}", yamlFile.getAbsolutePath(), e);
      return Collections.emptyMap();
    }
  }
}
