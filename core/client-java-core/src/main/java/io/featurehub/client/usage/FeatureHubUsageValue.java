package io.featurehub.client.usage;

import io.featurehub.client.EvaluatedFeature;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.FeatureValueType;
import java.util.Objects;
import java.util.UUID;
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
  final Object rawValue;
  @NotNull
  final FeatureValueType type;
  @NotNull
  final UUID environmentId;
  // this indicates the strategy-id (if any) that was used to determine this value. It indicates if the FHOS company
  // is tracking strategies which one actually triggered it.
  @Nullable
  final String strategyId;

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

  public @Nullable String getStrategyId() {
    return strategyId;
  }

  public @NotNull String getKey() { return key; }

  public @Nullable Object getRawValue() { return rawValue; }

  public @NotNull String getId() {
    return id;
  }

  public @Nullable String getValue() {
    return value;
  }

  public @NotNull FeatureValueType getType() {
    return type;
  }

  public @NotNull UUID getEnvironmentId() {
    return environmentId;
  }

  public FeatureHubUsageValue(EvaluatedFeature value) {
    FeatureState featureState = Objects.requireNonNull(value.getFeatureState());
    this.id = featureState.getId().toString();
    this.key = featureState.getKey();
    this.rawValue = value.getValue();
    this.type = Objects.requireNonNull(featureState.getType());
    this.value = convert(this.rawValue, this.type);
    this.environmentId = Objects.requireNonNull(featureState.getEnvironmentId());
    this.strategyId = value.getStrategyId();
  }
}
