package io.featurehub.client.utils;

import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.sse.model.FeatureValueType;
import java.math.BigDecimal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Conversion {
  private static final Logger log = LoggerFactory.getLogger(Conversion.class);

  @Nullable
  public static Object toTypedValue(@Nullable FeatureValueType type, @Nullable Object value, @NotNull String key, @NotNull InternalFeatureRepository repository) {
    if (type == FeatureValueType.BOOLEAN) {
      if (value == null) return Boolean.FALSE;
      if (value instanceof Boolean) return value;
      return "true".equalsIgnoreCase(value.toString());
    }

    if (value == null) return null;

    if (type == FeatureValueType.NUMBER) {
      if (value instanceof Number) return new BigDecimal(value.toString());
      try {
        return new BigDecimal(value.toString());
      } catch (Exception e) {
        log.debug("Cannot convert '{}' to a number for key '{}'", value, key);
        return null;
      }
    }

    if (type == FeatureValueType.STRING) {
      if (value instanceof String || value instanceof Boolean || value instanceof Number) {
        return value.toString();
      }
      return null;
    }

    if (type == FeatureValueType.JSON) {
      if (value instanceof String) {
        try {
          // is it JSON already? if so, return it as such
          repository.getJsonObjectMapper().readMapValue(value.toString());
          return value;
        } catch (Exception e) {
          // ignore
        }
      }

      return repository.getJsonObjectMapper().writeValueAsString(value);
    }

    // Unknown type — return primitives as-is (Number as BigDecimal), objects as JSON
    if (value instanceof Boolean || value instanceof String) return value;
    if (value instanceof Number) return new BigDecimal(value.toString());
    return repository.getJsonObjectMapper().writeValueAsString(value);
  }
}
