package io.featurehub.client.usage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public interface UsageProvider {
  default UsageFeature createUsageFeature(@NotNull FeatureHubUsageValue feature,
                                          @NotNull Map<String, List<String>> attributes) {
    return new UsageFeature(feature, attributes, null);
  }

  default UsageFeature createUsageFeature(@NotNull FeatureHubUsageValue feature,
                                          @Nullable Map<String, List<String>> attributes,
                                          @Nullable String userKey) {
    return new UsageFeature(feature, attributes, userKey);
  }

  default UsageFeaturesCollection createUsageCollectionEvent() {
    return new UsageFeaturesCollection();
  }

  default UsageFeaturesCollectionContext createUsageContextCollectionEvent() {
    return new UsageFeaturesCollectionContext();
  }

  class DefaultUsageProvider implements UsageProvider {}
}
