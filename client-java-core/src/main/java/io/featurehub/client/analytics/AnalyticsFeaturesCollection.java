package io.featurehub.client.analytics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnalyticsFeaturesCollection extends AnalyticsEvent {
  @NotNull List<FeatureHubAnalyticsValue> featureValues = new ArrayList<>();

  public AnalyticsFeaturesCollection(@Nullable String userKey, @NotNull Map<String, Object> additionalParams) {
    super(userKey, additionalParams);
  }

  public void setFeatureValues(List<FeatureHubAnalyticsValue> featureValues) {
    this.featureValues = featureValues;
  }

  public AnalyticsFeaturesCollection() {}

  void ready() {}

  @Override
  @NotNull Map<String, Object> toMap() {
    Map<String, Object> m = new HashMap<>(super.toMap());
    featureValues.forEach((fv) -> m.put(fv.key, fv.value));

    return Collections.unmodifiableMap(m);
  }
}
