package io.featurehub.sdk.yaml;

import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.FeatureValueType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads a YAML file in the same {@code flagValues} format as {@link LocalYamlValueInterceptor}
 * and pushes the entries as {@link FeatureState} objects directly into the repository via
 * {@link InternalFeatureRepository#updateFeatures(List, String)} with source {@code "local-yaml-store"}.
 *
 * <p>The file is read exactly once at construction time. No watching is performed.
 *
 * <p>Each feature's {@code id} is a deterministic UUID derived from a SHA-1 hash of the feature key,
 * and all features share the same {@code environmentId} UUID created when this instance is constructed.
 */
public class LocalYamlFeatureStore {
  private static final Logger log = LoggerFactory.getLogger(LocalYamlFeatureStore.class);
  static final String SOURCE = "local-yaml-store";

  public LocalYamlFeatureStore(@NotNull FeatureHubConfig config) {
    this(config, null);
  }

  public LocalYamlFeatureStore(@NotNull FeatureHubConfig config, @Nullable String filename) {
    final UUID environmentId = config.getEnvironmentId();
    InternalFeatureRepository repository = config.getInternalRepository();
    if (repository == null) {
      log.warn("FeatureHubConfig is closed; LocalYamlFeatureStore will not load features");
      return;
    }

    String resolved = filename != null
        ? filename
        : io.featurehub.client.FeatureHubConfig.getConfig(LocalYamlValueInterceptor.ENV_VAR,
            LocalYamlValueInterceptor.DEFAULT_FILE);

    Map<String, Object> flagValues = YamlLoader.readFlagValues(new File(resolved), log);

    if (flagValues.isEmpty()) {
      return;
    }

    List<FeatureState> features = new ArrayList<>(flagValues.size());
    for (Map.Entry<String, Object> entry : flagValues.entrySet()) {
      String key = entry.getKey();
      Object raw = entry.getValue();
      FeatureValueType type = detectType(raw);
      Object value = convertValue(raw, type, key, repository);

      features.add(new FeatureState()
          .id(keyToId(key))
          .key(key)
          .version(1L)
          .environmentId(environmentId)
          .type(type)
          .value(value)
          .l(false));
    }

    log.debug("Pushing {} feature(s) from local YAML store into repository", features.size());
    repository.updateFeatures(features, SOURCE);
  }

  private static FeatureValueType detectType(@Nullable Object value) {
    if (value instanceof Boolean) {
      return FeatureValueType.BOOLEAN;
    }
    if (value instanceof String) {
      String s = (String) value;
      if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
        return FeatureValueType.BOOLEAN;
      }
    }
    if (value == null) {
      return FeatureValueType.STRING;
    }
    if (value instanceof Number) {
      return FeatureValueType.NUMBER;
    }
    if (value instanceof String) {
      return FeatureValueType.STRING;
    }
    // Map, List, or any other non-scalar
    return FeatureValueType.JSON;
  }

  @Nullable
  private static Object convertValue(@Nullable Object value, FeatureValueType type, String key,
                                     InternalFeatureRepository repository) {
    switch (type) {
      case BOOLEAN:
        if (value instanceof Boolean) return value;
        return Boolean.parseBoolean(value.toString());
      case NUMBER:
        return new BigDecimal(value.toString());
      case STRING:
        return value == null ? null : value.toString();
      case JSON:
        return repository.getJsonObjectMapper().writeValueAsString(value);
      default:
        log.warn("Unhandled FeatureValueType {} for key '{}'", type, key);
        return null;
    }
  }

  /**
   * Returns a deterministic UUID for a feature key by taking the first 16 bytes of its SHA-1 hash.
   */
  static UUID keyToId(String key) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      byte[] hash = sha1.digest(key.getBytes(StandardCharsets.UTF_8));
      long msb = 0, lsb = 0;
      for (int i = 0; i < 8; i++) msb = (msb << 8) | (hash[i] & 0xFF);
      for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xFF);
      return new UUID(msb, lsb);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-1 not available", e);
    }
  }
}
