package io.featurehub.client.usage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UsageEventWithFeature extends UsageEvent implements UsageEventName {
  @Nullable
  final Map<String, List<String>> attributes;
  @NotNull final FeatureHubUsageValue feature;

  public UsageEventWithFeature(@NotNull FeatureHubUsageValue feature, @Nullable Map<String, List<String>> attributes,
                               @Nullable String userKey) {
    this.attributes = attributes;
    this.feature = feature;
    setUserKey(userKey);
  }

  @Nullable public Map<String, List<String>> getAttributes() {
    return attributes;
  }

  @NotNull public FeatureHubUsageValue getFeature() {
    return feature;
  }

  @Override
  public @NotNull String getEventName() {
    return "feature";
  }

  @Override
  @NotNull public Map<String, Object> toMap() {
    Map<String, Object> m = new HashMap<>(super.toMap());

    if (attributes != null) { // may not be from a context
      m.putAll(attributes);
    }
    m.put("feature", feature.key);
    m.put("value", feature.value);
    m.put("id", feature.id);

    return Collections.unmodifiableMap(m);
  }
}
