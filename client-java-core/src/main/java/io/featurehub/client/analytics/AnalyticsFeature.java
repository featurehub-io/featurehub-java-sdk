package io.featurehub.client.analytics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnalyticsFeature extends AnalyticsEvent implements AnalyticsEventName {
  @NotNull
  final Map<String, List<String>> attributes;
  @NotNull final FeatureHubAnalyticsValue feature;

  public AnalyticsFeature(@NotNull FeatureHubAnalyticsValue feature, @NotNull Map<String, List<String>> attributes,
                          @Nullable String userKey) {
    this.attributes = attributes;
    this.feature = feature;
  }

  @NotNull public Map<String, List<String>> getAttributes() {
    return attributes;
  }

  @NotNull public FeatureHubAnalyticsValue getFeature() {
    return feature;
  }

  @Override
  public @NotNull String getEventName() {
    return "feature";
  }

  @Override
  @NotNull Map<String, Object> toMap() {
    Map<String, Object> m = new HashMap<>(super.toMap());

    m.putAll(attributes);
    m.put("feature", feature.key);
    m.put("value", feature.id);
    m.put("id", feature.id);

    return Collections.unmodifiableMap(m);
  }
}
