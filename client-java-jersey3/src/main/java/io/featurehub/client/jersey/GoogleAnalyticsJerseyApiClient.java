package io.featurehub.client.jersey;

import io.featurehub.client.GoogleAnalyticsApiClient;

import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;

public class GoogleAnalyticsJerseyApiClient implements GoogleAnalyticsApiClient {
  private final WebTarget target;

  public GoogleAnalyticsJerseyApiClient() {
    target = ClientBuilder.newBuilder()
      .build().target("https://www.google-analytics.com/batch");
  }

  @Override
  public void postBatchUpdate(String batchData) {
    target.request().header("Host", "www.google-analytics.com").post(Entity.entity(batchData, MediaType.APPLICATION_FORM_URLENCODED_TYPE));
  }
}
