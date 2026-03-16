package io.featurehub.client.usage;


import java.util.List;

public interface UsageFeaturesCollection extends UsageEvent {
  void setFeatureValues(List<FeatureHubUsageValue> featureValues);
}
