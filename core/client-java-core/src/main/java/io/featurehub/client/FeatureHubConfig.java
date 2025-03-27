package io.featurehub.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsagePlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface FeatureHubConfig {

  /**
   * Use environment variables to create a system config.
   * @return system config
   */
  default FeatureHubConfig envConfig() {
    return new EdgeFeatureHubConfig(System.getenv("FEATUREHUB_EDGE_URL"), System.getenv("FEATUREHUB_API_KEY"));
  }

  /**
   * Use system properties to create a system config.
   * @return system config
   */
  default FeatureHubConfig systemPropertyConfig() {
    return new EdgeFeatureHubConfig(System.getProperty("featurehub.edge-url"), System.getProperty("featurehub.api-key"));
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
  @NotNull FeatureRepository getRepository();
  @NotNull InternalFeatureRepository getInternalRepository();

  FeatureHubConfig setEdgeService(Supplier<EdgeService> edgeService);
  @NotNull Supplier<EdgeService> getEdgeService();

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
  FeatureHubConfig registerValueInterceptor(boolean allowLockOverride, @NotNull FeatureValueInterceptor interceptor);

  FeatureHubConfig registerUsagePlugin(@NotNull UsagePlugin plugin);

  /**
   * Allows you to query the state of the repository's readyness - such as in a heartbeat API
   * @return
   */
  @NotNull Readiness getReadiness();

  /**
   * Allows you to override how your config will be deserialized when "getJson" is called.
   *
   * @param jsonConfigObjectMapper - a Jackson ObjectMapper
   */
  FeatureHubConfig setJsonConfigObjectMapper(ObjectMapper jsonConfigObjectMapper);

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
}
