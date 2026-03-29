package io.featurehub.client.usage;

import java.util.*;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DefaultUsageFeaturesCollection extends DefaultUsageEvent implements UsageFeaturesCollection {
  @NotNull List<FeatureHubUsageValue> featureValues = new ArrayList<>();

  public DefaultUsageFeaturesCollection(@Nullable String userKey, @Nullable Map<String, Object> additionalParams) {
    super(userKey, additionalParams);
  }

  public DefaultUsageFeaturesCollection() {
    super();
  }

  public void setFeatureValues(List<FeatureHubUsageValue> featureValues) {
    this.featureValues = featureValues;
  }

  @Override
  public @NotNull List<FeatureHubUsageValue> getFeatureValues() {
    return new ArrayList<>(featureValues);
  }

  void ready() {}

  @Override
  @NotNull public Map<String, ?> toMap() {
    Map<String, Object> m = new HashMap<>(super.toMap());

    featureValues.forEach((fv) -> {
      m.put(fv.key, fv.value);
    });

    return Collections.unmodifiableMap(m);
  }
}
