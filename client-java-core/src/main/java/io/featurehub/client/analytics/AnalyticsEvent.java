package io.featurehub.client.analytics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class AnalyticsEvent {
  @Nullable
  private String userKey;
  @NotNull
  private Map<String, Object> additionalParams = new HashMap<>();

  public AnalyticsEvent(@Nullable String userKey) {
    this.userKey = userKey;
  }

  public AnalyticsEvent() {
  }

  public void setUserKey(String userKey) {
    this.userKey = userKey;
  }

  public void setAdditionalParams(Map<String, Object> additionalParams) {
    this.additionalParams = additionalParams;
  }

  public AnalyticsEvent(@Nullable String userKey, @NotNull Map<String, Object> additionalParams) {
    this.userKey = userKey;
    this.additionalParams = additionalParams;
  }

  @NotNull
  Map<String, Object> toMap() {
    return additionalParams;
  }
}
