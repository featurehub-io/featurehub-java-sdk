package io.featurehub.client.jersey;

import cd.connect.openapi.support.ApiClient;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.TestApi;
import io.featurehub.client.TestApiResult;
import io.featurehub.sse.model.FeatureStateUpdate;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.jetbrains.annotations.NotNull;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;

/**
 * This makes a simple wrapper around the TestSDK Client
 */
public class TestSDKClient implements TestApi {
  private final FeatureServiceImpl featureService;
  private final FeatureHubConfig config;

  public TestSDKClient(FeatureHubConfig config) {
    this.config = config;
    Client client = ClientBuilder.newBuilder()
      .register(JacksonFeature.class).build();

    final ApiClient apiClient = new ApiClient(client, config.baseUrl());

    featureService = new FeatureServiceImpl(apiClient);
  }

  public @NotNull TestApiResult setFeatureState(String apiKey, @NotNull String featureKey,
                              @NotNull FeatureStateUpdate featureStateUpdate) {
    return new TestApiResult(featureService.setFeatureState(apiKey, featureKey, featureStateUpdate, null));
  }

  @Override
  public @NotNull TestApiResult setFeatureState(@NotNull String featureKey, @NotNull FeatureStateUpdate featureStateUpdate) {
    return new TestApiResult(featureService.setFeatureState(config.apiKey(), featureKey, featureStateUpdate, null));
  }

  @Override
  public void close() {
  }
}
