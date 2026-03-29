package io.featurehub.client;

import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsageProvider;
import io.featurehub.javascript.JavascriptObjectMapper;
import io.featurehub.sse.model.FeatureRolloutStrategy;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.SSEResultState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface InternalFeatureRepository extends FeatureRepository {

  /*
   * Any incoming state changes from a multi-varied set of possible data. This comes
   * from SSE.
   */
  default void notify(@NotNull SSEResultState state) { notify(state, "unknown"); }
  void notify(@NotNull SSEResultState state, @NotNull String source);

  /**
   * Indicate the feature states have updated and if their versions have
   * updated or no versions exist, update the repository.
   *
   * @param features - the features
   */
  default void updateFeatures(@NotNull List<FeatureState> features) { updateFeatures(features, "unknown"); }
  void updateFeatures(@NotNull List<FeatureState> features, @NotNull String source);
  /**
   * Update the feature states and force them to be updated, ignoring their version numbers.
   * This still may not cause events to be triggered as event triggers are done on actual value changes.
   *
   * @param features - the list of feature states
   * @param force  - whether we should force the states to change
   */
  default void updateFeatures(@NotNull List<FeatureState> features, boolean force) { updateFeatures(features, force, "unknown"); }
  void updateFeatures(@NotNull List<FeatureState> features, boolean force, @NotNull String source);
  default boolean updateFeature(@NotNull FeatureState feature) { return updateFeature(feature, "unknown"); }
  boolean updateFeature(@NotNull FeatureState feature, @NotNull String source);
  default boolean updateFeature(@NotNull FeatureState feature, boolean force) { return updateFeature(feature, force, "unknown"); }
  boolean updateFeature(@NotNull FeatureState feature, boolean force, @NotNull String source);
  default void deleteFeature(@NotNull FeatureState feature) { deleteFeature(feature, "unknown"); }
  void deleteFeature(@NotNull FeatureState feature, @NotNull String source);

  @Deprecated
  @Nullable FeatureValueInterceptor.ValueMatch findIntercept(boolean locked, @NotNull String key);
  // findIntercept here will never return null, but a false match with a null value
  @NotNull ExtendedFeatureValueInterceptor.ValueMatch findIntercept(@NotNull String key, @Nullable FeatureState featureState);

  @NotNull Applied applyFeature(@NotNull List<FeatureRolloutStrategy> strategies, @NotNull String key, @NotNull String featureValueId,
                                @NotNull ClientContext cac);

  void execute(@NotNull Runnable command);
  ExecutorService getExecutor();

  @NotNull JavascriptObjectMapper getJsonObjectMapper();

  /**
   * Tell the repository that its features are not in a valid state. Only called by server eval context.
   */
  void repositoryNotReady();

  void close();

  @NotNull Readiness getReadiness();

  @NotNull FeatureStateBase<?> getFeat(@NotNull String key);
  @NotNull FeatureStateBase<?> getFeat(@NotNull Feature key);
  @NotNull <K> FeatureStateBase<K> getFeat(@NotNull String key, @NotNull Class<K> clazz);

  void recordUsageEvent(@NotNull UsageEvent event);

  /**
   * Repository is empty, there are no features but repository is ready.
   */
  void repositoryEmpty();

  void used(EvaluatedFeature value,
            @Nullable Map<String, @Nullable List<String>> attributes,
            @Nullable String usageUserKey);

  @NotNull UsageProvider getUsageProvider();
}
