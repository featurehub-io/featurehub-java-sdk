package io.featurehub.client;

import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsagePlugin;
import io.featurehub.javascript.JavascriptObjectMapper;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface FeatureHubConfig {

  static @Nullable String getConfig(@NotNull String name)  {
    String val = System.getenv(name);
    if (val == null) {
      val = System.getProperty(name);

      if (val == null) {
        val = System.getenv(name.toUpperCase());
        if (val == null) {
          val = System.getenv(name.toUpperCase().replace('.', '_').replace('-', '_'));
        }
      }
    }

    return val;
  }

  static String getRequiredConfig(@NotNull String name) {
    String val = getConfig(name);

    if (val == null) {
      throw new RuntimeException(String.format("Required configuration `%s` is missing!", name));
    }

    return val;
  }

  static @NotNull String getConfig(@NotNull String name, @NotNull String defaultVal) {
    String val = getConfig(name);

    return val == null ? defaultVal : val;
  }

  /**
   * What is the fully deconstructed URL for the server?
   */
  @NotNull String getRealtimeUrl();

  @NotNull String apiKey();
  @NotNull List<@NotNull String> apiKeys();

  @NotNull String baseUrl();

  /**
   * If you are using a client evaluated feature context, this will initialise the service and block until
   * the provided timeout or until you have received your first set of features. Server Evaluated contexts
   * should not use it because it needs to re-request data from the server each time you change your context.
   */
  void init(long timeout, TimeUnit unit);

  /**
   * If you are using a client evaluated feature context, this will initialise the service and block until
   * you have received your first set of features. Server Evaluated contexts should not use it because it needs
   * to re-request data from the server each time you change your context.
   */
  Future<ClientContext> init();

  /**
   * The API Key indicates this is going to be server based evaluation
   */
  boolean isServerEvaluation();

  /**
   * returns a new context using the default edge provider and repository
   *
   * @return a new context
   */
  @NotNull ClientContext newContext();

  static boolean sdkKeyIsClientSideEvaluated(Collection<String> sdkKey) {
    return sdkKey.stream().anyMatch(key -> key != null && key.contains("*"));
  }

  FeatureHubConfig setRepository(FeatureRepository repository);
  @Nullable FeatureRepository getRepository();
  @Nullable InternalFeatureRepository getInternalRepository();

  FeatureHubConfig setEdgeService(Supplier<EdgeService> edgeService);
  @Nullable Supplier<EdgeService> getEdgeService();

  /**
   * Returns true if {@link #close()} has been called on this config.
   */
  default boolean isClosed() { return false; }

  /**
   * Allows you to specify a readyness listener to trigger every time the repository goes from
   * being in any way not reaay, to ready.
   * @param readinessListener
   */
  @NotNull RepositoryEventHandler addReadinessListener(@NotNull Consumer<Readiness> readinessListener);

  /**
   * Allows you to register a value interceptor
   * @param allowLockOverride
   * @param interceptor
   */
  @Deprecated
  FeatureHubConfig registerValueInterceptor(boolean allowLockOverride, @NotNull FeatureValueInterceptor interceptor);
  FeatureHubConfig registerValueInterceptor(@NotNull ExtendedFeatureValueInterceptor interceptor);
  FeatureHubConfig registerRawUpdateFeatureListener(@NotNull RawUpdateFeatureListener listener);

  FeatureHubConfig registerUsagePlugin(@NotNull UsagePlugin plugin);

  /**
   * Allows you to query the state of the repository's readyness - such as in a heartbeat API
   * @return
   */
  @NotNull Readiness getReadiness();

  /**
   * Returns true if the repository is in the Ready state.
   */
  default boolean isReady() { return getReadiness() == Readiness.Ready; }

  /**
   * Blocks until the repository reaches the Ready state or the timeout elapses.
   * Calls poll() on the edge service to trigger an initial data fetch, then
   * rechecks readiness every 200 ms.
   *
   * @param timeout maximum time to wait
   * @param unit    time unit for the timeout
   * @return true if ready within the timeout, false if the timeout elapsed or the thread was interrupted
   */
  boolean waitForReady(long timeout, TimeUnit unit);

  /**
   * Blocks for at most 10 seconds until the repository reaches the Ready state.
   * Returns false if the thread is interrupted.
   */
  default boolean waitForReady() { return waitForReady(10, TimeUnit.SECONDS); }

  /**
   * Allows you to override how your config will be deserialized when "getJson" is called.
   *
   * @param jsonConfigObjectMapper - a Jackson ObjectMapper
   */
  FeatureHubConfig setJsonConfigObjectMapper(JavascriptObjectMapper jsonConfigObjectMapper);

  /**
   * You should use this close if you are using a client evaluated key and wish to close the connection to the remote
   * server cleanly
   */
  void close();

  FeatureHubConfig streaming();

  /**
   * interval defaults to 180 seconds
   */
  FeatureHubConfig restActive();
  FeatureHubConfig restActive(int intervalInSeconds);
  FeatureHubConfig restPassive(int cacheTimeoutInSeconds);

  /**
   * cache timeout defaults to 180 seconds
   */
  FeatureHubConfig restPassive();

  FeatureHubConfig recordUsageEvent(UsageEvent event);

  /**
   * Gets the EnvironmentID of the
   * @return
   */
  UUID getEnvironmentId();

}
