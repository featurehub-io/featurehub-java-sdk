package io.featurehub.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsageProvider;
import io.featurehub.sse.model.FeatureRolloutStrategy;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.FeatureValueType;
import io.featurehub.sse.model.SSEResultState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

public interface InternalFeatureRepository extends FeatureRepository {

  /*
   * Any incoming state changes from a multi-varied set of possible data. This comes
   * from SSE.
   */
  void notify(@NotNull SSEResultState state);

  /**
   * Indicate the feature states have updated and if their versions have
   * updated or no versions exist, update the repository.
   *
   * @param features - the features
   */
  void updateFeatures(@NotNull List<FeatureState> features);
  /**
   * Update the feature states and force them to be updated, ignoring their version numbers.
   * This still may not cause events to be triggered as event triggers are done on actual value changes.
   *
   * @param features - the list of feature states
   * @param force  - whether we should force the states to change
   */
  void updateFeatures(@NotNull List<FeatureState> features, boolean force);
  boolean updateFeature(@NotNull FeatureState feature);
  boolean updateFeature(@NotNull FeatureState feature, boolean force);
  void deleteFeature(@NotNull FeatureState feature);

  @Nullable FeatureValueInterceptor.ValueMatch findIntercept(boolean locked, @NotNull String key);

  @NotNull Applied applyFeature(@NotNull List<FeatureRolloutStrategy> strategies, @NotNull String key, @NotNull String featureValueId,
                                @NotNull ClientContext cac);

  void execute(@NotNull Runnable command);
  Executor getExecutor();

  @NotNull ObjectMapper getJsonObjectMapper();

  /**
   * Tell the repository that its features are not in a valid state. Only called by server eval context.
   */
  void repositoryNotReady();

  void close();

  @NotNull Readiness getReadiness();

  @NotNull FeatureStateBase<?> getFeat(@NotNull String key);
  @NotNull FeatureStateBase<?> getFeat(@NotNull Feature key);
  @NotNull <K> FeatureStateBase<K> getFeat(@NotNull String key, @NotNull Class<K> clazz);

  void recordAnalyticsEvent(@NotNull UsageEvent event);

  /**
   * Repository is empty, there are no features but repository is ready.
   */
  void repositoryEmpty();

  void used(@NotNull String key, @NotNull UUID id, @NotNull FeatureValueType valueType, @Nullable Object value,
            @Nullable Map<String, List<String>> attributes,
            @Nullable String analyticsUserKey);

  @NotNull UsageProvider getAnalyticsProvider();
}
