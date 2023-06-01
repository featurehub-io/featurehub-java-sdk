package io.featurehub.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.featurehub.client.analytics.AnalyticsEvent;
import io.featurehub.client.analytics.AnalyticsProvider;
import io.featurehub.sse.model.FeatureRolloutStrategy;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.FeatureValueType;
import io.featurehub.sse.model.SSEResultState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface InternalFeatureRepository extends FeatureRepository {

  /*
   * Any incoming state changes from a multi-varied set of possible data. This comes
   * from SSE.
   */
  void notify(SSEResultState state);

  /**
   * Indicate the feature states have updated and if their versions have
   * updated or no versions exist, update the repository.
   *
   * @param features - the features
   */
  void updateFeatures(List<FeatureState> features);
  /**
   * Update the feature states and force them to be updated, ignoring their version numbers.
   * This still may not cause events to be triggered as event triggers are done on actual value changes.
   *
   * @param features - the list of feature states
   * @param force  - whether we should force the states to change
   */
  void updateFeatures(List<FeatureState> features, boolean force);
  boolean updateFeature(FeatureState feature);
  boolean updateFeature(FeatureState feature, boolean force);
  void deleteFeature(FeatureState feature);

  FeatureValueInterceptor.ValueMatch findIntercept(boolean locked, String key);

  Applied applyFeature(List<FeatureRolloutStrategy> strategies, String key, String featureValueId,
                       ClientContext cac);

  void execute(Runnable command);

  ObjectMapper getJsonObjectMapper();

  void setServerEvaluation(boolean val);

  /**
   * Tell the repository that its features are not in a valid state.
   */
  void repositoryNotReady();

  void close();

  @NotNull Readiness getReadiness();

  FeatureStateBase<?> getFeat(String key);
  <K> FeatureStateBase<K> getFeat(String key, Class<K> clazz);

  void recordAnalyticsEvent(AnalyticsEvent event);

  /**
   * Only called by server eval context when we swap context
   */

  /**
   * Repository is empty, there are no features but repository is ready.
   */
  void repositoryEmpty();

  void used(@NotNull String key, @NotNull UUID id, @NotNull FeatureValueType valueType, @Nullable Object value,
            @NotNull Map<String, List<String>> attributes,
            @Nullable String analyticsUserKey);

  AnalyticsProvider getAnalyticsProvider();
}
