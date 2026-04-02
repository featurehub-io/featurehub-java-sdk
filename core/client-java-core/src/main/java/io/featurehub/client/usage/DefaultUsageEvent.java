package io.featurehub.client.usage;

import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DefaultUsageEvent implements UsageEvent {
  /**
   * This is the unique identifying key of the user for this event (if any)
   */
  @Nullable
  private String userKey;
  /**
   * This is the set of any additional parameters that a user wishes to collect over and above the context attributes
   */
  @NotNull
  private Map<String, Object> additionalParams = new HashMap<>();

  public DefaultUsageEvent(@Nullable String userKey) {
    this.userKey = userKey;
  }

  public DefaultUsageEvent() {
  }

  public DefaultUsageEvent(@Nullable String userKey, @Nullable Map<String, Object> additionalParams) {
    this.userKey = userKey;
    if (additionalParams != null) {
      this.additionalParams = additionalParams;
    }
  }

  @Override
  public void setUserKey(String userKey) {
    this.userKey = userKey;
  }

  public void setAdditionalParams(@NotNull Map<String, Object> additionalParams) {
    this.additionalParams = additionalParams;
  }

  @Override
  @NotNull
  public Map<String, ?> toMap() {
    return additionalParams;
  }

  @Override
  @Nullable
  public String getUserKey() {
    return userKey;
  }
}
