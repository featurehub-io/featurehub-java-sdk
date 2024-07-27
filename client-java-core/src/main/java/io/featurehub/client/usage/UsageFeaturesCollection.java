package io.featurehub.client.usage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UsageFeaturesCollection extends UsageEvent {
  @NotNull List<FeatureHubUsageValue> featureValues = new ArrayList<>();

  public UsageFeaturesCollection(@Nullable String userKey, @Nullable Map<String, Object> additionalParams) {
    super(userKey, additionalParams);
  }

  public void setFeatureValues(List<FeatureHubUsageValue> featureValues) {
    this.featureValues = featureValues;
  }

  public UsageFeaturesCollection() {}

  void ready() {}

  @Override
  @NotNull public Map<String, Object> toMap() {
    Map<String, Object> m = new HashMap<>(super.toMap());
    featureValues.forEach((fv) -> m.put(fv.key, fv.value));

    return Collections.unmodifiableMap(m);
  }
}
