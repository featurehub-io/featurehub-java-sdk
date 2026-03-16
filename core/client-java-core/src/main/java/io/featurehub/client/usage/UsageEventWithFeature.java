package io.featurehub.client.usage;

import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface UsageEventWithFeature extends UsageEvent, UsageEventName {
  @Nullable Map<String, List<String>> getAttributes();
  @NotNull FeatureHubUsageValue getFeature();
}
