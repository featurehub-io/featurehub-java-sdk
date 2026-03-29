package io.featurehub.client;

import io.featurehub.sse.model.FeatureState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EvaluatedFeature {
  @Nullable
  private final Object value;
  @Nullable
  private final String strategyId;
  @Nullable
  private final FeatureState featureState;

  private EvaluatedFeature(@Nullable FeatureState fs, @Nullable Object value, @Nullable String strategyId) {
    this.value = value;
    this.strategyId = strategyId;
    this.featureState = fs;
  }

  public boolean isNull() {
    return value == null;
  }

  public static EvaluatedFeature from(@NotNull FeatureState fs, @Nullable Object value, @Nullable String strategyId) {
    return new EvaluatedFeature(fs, value, strategyId);
  }

  // this can be used by the interceptor or normal logic
  public static EvaluatedFeature from(@Nullable FeatureState fs, @Nullable Object value) {
    return new EvaluatedFeature(fs, value, null);
  }

  // this is only used by the interceptor when there are phantom features and never generates a usage track
  public static EvaluatedFeature from(@Nullable Object value) {
    return new EvaluatedFeature(null, value, null);
  }

  // if this is used, it means grab the value from the featurestate
  public static EvaluatedFeature from(@NotNull FeatureState fs) {
    return new EvaluatedFeature(fs, fs.getValue(), null);
  }

  public @Nullable Object getValue() {
    return value;
  }

  public @Nullable String getStrategyId() {
    return strategyId;
  }

  public @Nullable FeatureState getFeatureState() {
    return featureState;
  }

  @Override
  public String toString() {
    return "InternalValueTuple{" +
      "value=" + value +
      ", strategyId='" + strategyId + '\'' +
      '}';
  }
}
