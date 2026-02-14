package io.featurehub.client.jersey;

import cd.connect.openapi.support.ApiResponse;
import io.featurehub.sse.model.FeatureEnvironmentCollection;
import io.featurehub.sse.model.FeatureStateUpdate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public interface FeatureService {
  @NotNull ApiResponse<List<FeatureEnvironmentCollection>> getFeatureStates(@NotNull List<String> apiKey,
                                                                            @Nullable String contextSha,
                                                                            @Nullable Map<String, String> extraHeaders);
  int setFeatureState(@NotNull String apiKey,
                      @NotNull String featureKey,
                      @NotNull FeatureStateUpdate featureStateUpdate,
                      @Nullable Map<String, String> extraHeaders);
}
