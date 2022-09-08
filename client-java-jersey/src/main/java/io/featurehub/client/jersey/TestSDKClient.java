package io.featurehub.client.jersey;

import cd.connect.openapi.support.ApiClient;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.sse.model.FeatureStateUpdate;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.jetbrains.annotations.NotNull;

/**
 * This makes a simple wrapper around the TestSDK Client
 */
public class TestSDKClient {
  private final FeatureServiceImpl featureService;

  public TestSDKClient(FeatureHubConfig config) {
    Client client = ClientBuilder.newBuilder()
      .register(JacksonFeature.class).build();

    final ApiClient apiClient = new ApiClient(client, config.baseUrl());

    featureService = new FeatureServiceImpl(apiClient);
  }

  public void setFeatureState(String apiKey, @NotNull String featureKey,
                              @NotNull FeatureStateUpdate featureStateUpdate) {
    featureService.setFeatureState(apiKey, featureKey, featureStateUpdate);
  }
}
