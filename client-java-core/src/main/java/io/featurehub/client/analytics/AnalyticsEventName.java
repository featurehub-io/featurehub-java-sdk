package io.featurehub.client.analytics;

import org.jetbrains.annotations.NotNull;

public interface AnalyticsEventName {
  @NotNull String getEventName();
}
