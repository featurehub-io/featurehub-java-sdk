package io.featurehub.client;

import io.featurehub.sse.model.FeatureStateUpdate;
import org.jetbrains.annotations.NotNull;

public interface TestApi {
  @NotNull TestApiResult setFeatureState(String apiKey, @NotNull String featureKey,
                              @NotNull FeatureStateUpdate featureStateUpdate);
  @NotNull TestApiResult setFeatureState(@NotNull String featureKey,
                              @NotNull FeatureStateUpdate featureStateUpdate);

  void close();
}
