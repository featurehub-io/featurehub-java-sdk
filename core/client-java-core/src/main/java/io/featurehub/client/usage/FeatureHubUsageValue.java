package io.featurehub.client.usage;

import io.featurehub.client.FeatureStateBase;
import io.featurehub.sse.model.FeatureValueType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class FeatureHubUsageValue {
  @NotNull
  final String id;
  @NotNull
  final String key;
  @Nullable
  final String value;

  @Nullable
  static String convert(@Nullable Object value, @Nullable FeatureValueType type) {
    if (type == null || value == null) {
      return null;
    }

    switch (type) {
      case BOOLEAN:
        return Boolean.TRUE.equals(value) ? "on" : "off";
      case STRING:
      case NUMBER:
        return value.toString();
      case JSON:
        return null;
    }

    return null;
  }

  public FeatureHubUsageValue(@NotNull String id, @NotNull String key, @Nullable Object value,
                              @NotNull FeatureValueType type) {
    this.id = id;
    this.key = key;
    this.value = convert(value, type);
  }

  public FeatureHubUsageValue(@NotNull FeatureStateBase<?> holder) {
    this.id = holder.getId();
    this.key = holder.getKey();
    this.value = convert(holder.getUsageFreeValue(), holder.getType());
  }
}
