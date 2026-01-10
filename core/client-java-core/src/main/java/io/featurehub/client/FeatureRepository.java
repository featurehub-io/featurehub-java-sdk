package io.featurehub.client;

import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsageProvider;
import io.featurehub.javascript.JavascriptObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

public interface FeatureRepository {
  /**
   * Changes in readyness for the repository. It can become ready and then fail if subsequent
   * calls fail.
   *
   * @param readinessListener - a callback lambda
   * @return - this FeatureRepository
   */
  @NotNull RepositoryEventHandler addReadinessListener(@NotNull Consumer<Readiness> readinessListener);

  @NotNull List<FeatureState<?>> getAllFeatures();
  @NotNull Set<String> getFeatureKeys();
  @NotNull FeatureState<?> feature(String key);
  @NotNull <K> FeatureState<K> feature(String key, Class<K> clazz);

  /**
   * Adds interceptor support for feature values.
   *
   * @param allowLockOverride - is this interceptor allowed to override the lock? i.e. if the feature is locked, we
   *                          ignore the interceptor
   * @param interceptor       - the interceptor
   * @return the instance of the repo for chaining
   */
  @NotNull FeatureRepository registerValueInterceptor(boolean allowLockOverride, @NotNull FeatureValueInterceptor interceptor);
  void registerUsageProvider(@NotNull UsageProvider provider);

  @NotNull RepositoryEventHandler registerNewFeatureStateAvailable(@NotNull Consumer<FeatureRepository> callback);
  @NotNull RepositoryEventHandler registerFeatureUpdateAvailable(@NotNull Consumer<FeatureState<?>> callback);
  @NotNull RepositoryEventHandler registerUsageStream(@NotNull Consumer<UsageEvent> callback);

  @NotNull Readiness getReadiness();

  /**
   * Lets the SDK override the configuration of the JSON mapper in case they have special techniques they use.
   *
   * @param jsonConfigObjectMapper - an ObjectMapper configured for client use. This defaults to the same one
   *                               used to deserialize
   */
  void setJsonConfigObjectMapper(@NotNull JavascriptObjectMapper jsonConfigObjectMapper);

  void close();
}
