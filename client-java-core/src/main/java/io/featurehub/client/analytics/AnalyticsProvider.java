package io.featurehub.client.analytics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public interface AnalyticsProvider {
  default AnalyticsFeature createAnalyticsFeature(@NotNull FeatureHubAnalyticsValue feature,
                                                  @NotNull Map<String, List<String>> attributes) {
    return new AnalyticsFeature(feature, attributes, null);
  }

  default AnalyticsFeature createAnalyticsFeature(@NotNull FeatureHubAnalyticsValue feature,
                                                  @Nullable Map<String, List<String>> attributes,
                                                  @Nullable String userKey) {
    return new AnalyticsFeature(feature, attributes, userKey);
  }

  default AnalyticsFeaturesCollection createAnalyticsCollectionEvent() {
    return new AnalyticsFeaturesCollection();
  }

  default AnalyticsFeaturesCollectionContext createAnalyticsContextCollectionEvent() {
    return new AnalyticsFeaturesCollectionContext();
  }

  class DefaultAnalyticsProvider implements AnalyticsProvider {}
}
