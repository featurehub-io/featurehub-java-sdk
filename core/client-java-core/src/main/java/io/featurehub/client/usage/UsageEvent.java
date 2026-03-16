package io.featurehub.client.usage;

import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface UsageEvent {
  @Nullable String getUserKey();
  void setUserKey(@Nullable String userKey);
  void setAdditionalParams(@NotNull Map<String, Object> additionalParams);
  @NotNull Map<String, ?> toMap();
}
