package io.featurehub.client.usage;

import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface UsageProvider {
  default UsageEventWithFeature createUsageFeature(@NotNull FeatureHubUsageValue feature,
                                                   @NotNull Map<String, List<String>> attributes) {
    return new DefaultUsageEventWithFeature(feature, attributes, null);
  }

  default UsageEventWithFeature createUsageFeature(@NotNull FeatureHubUsageValue feature,
                                                   @Nullable Map<String, List<String>> attributes,
                                                   @Nullable String userKey) {
    return new DefaultUsageEventWithFeature(feature, attributes, userKey);
  }

  default UsageFeaturesCollection createUsageCollectionEvent() {
    return new DefaultUsageFeaturesCollection();
  }

  default UsageFeaturesCollectionContext createUsageContextCollectionEvent() {
    return new DefaultUsageFeaturesCollectionContext();
  }

  default UsageEvent createUsageEvent() {
    return new DefaultUsageEvent();
  }

  default UsageEvent createUsageEvent(@Nullable String userKey) {
    return new DefaultUsageEvent(userKey);
  }

  default UsageEvent createUsageEvent(@Nullable String userKey, @Nullable Map<String, Object> additionalParams) {
    return new DefaultUsageEvent(userKey, additionalParams);
  }

  default UsageEventWithFeature createUsageEventWithFeature(@NotNull FeatureHubUsageValue feature, @Nullable Map<String, List<String>> attributes,
                                                                                         @Nullable String userKey) {
    return new DefaultUsageEventWithFeature(feature, attributes, userKey);
  }

  class DefaultUsageProvider implements UsageProvider {}
}
