package io.featurehub.client.usage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class UsageEvent {
  @Nullable
  private String userKey;
  @NotNull
  private Map<String, Object> additionalParams = new HashMap<>();

  public UsageEvent(@Nullable String userKey) {
    this.userKey = userKey;
  }

  public UsageEvent() {
  }

  public void setUserKey(String userKey) {
    this.userKey = userKey;
  }

  public void setAdditionalParams(@NotNull Map<String, Object> additionalParams) {
    this.additionalParams = additionalParams;
  }

  public UsageEvent(@Nullable String userKey, @Nullable Map<String, Object> additionalParams) {
    this.userKey = userKey;
    if (additionalParams != null) {
      this.additionalParams = additionalParams;
    }
  }

  @NotNull
  protected Map<String, Object> toMap() {
    return additionalParams;
  }
}
