package io.featurehub.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.featurehub.client.usage.UsageProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface FeatureHubConfig {
  /**
   * What is the fully deconstructed URL for the server?
   */
  @NotNull String getRealtimeUrl();

  @NotNull String apiKey();
  @NotNull List<@NotNull String> apiKeys();

  @NotNull String baseUrl();

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
    return sdkKey.stream().anyMatch(key -> key.contains("*"));
  }

  void setRepository(FeatureRepository repository);
  @NotNull FeatureRepository getRepository();

  void setEdgeService(Supplier<EdgeService> edgeService);
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
  void registerValueInterceptor(boolean allowLockOverride, FeatureValueInterceptor interceptor);

  /**
   * Allows the user to register a new usage provider that determines what internal classes are
   * created on analytical events
   * @param provider
   */
  void registerUsageProvider(@NotNull UsageProvider provider);

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
  void setJsonConfigObjectMapper(ObjectMapper jsonConfigObjectMapper);

  /**
   * You should use this close if you are using a client evaluated key and wish to close the connection to the remote
   * server cleanly
   */
  void close();

  FeatureHubConfig streaming();

  /**
   * interval defaults to 180 seconds
   */
  FeatureHubConfig restPoll();
  FeatureHubConfig restPoll(int intervalInSeconds);
  FeatureHubConfig rest(int cacheTimeoutInSeconds);

  /**
   * cache timeout defaults to 180 seconds
   */
  FeatureHubConfig rest();
}
