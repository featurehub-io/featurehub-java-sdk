package io.featurehub.okhttp;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.TestApi;
import io.featurehub.client.TestApiResult;
import io.featurehub.client.utils.SdkVersion;
import io.featurehub.sse.model.FeatureStateUpdate;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class TestClient implements TestApi {
  private static final Logger log = LoggerFactory.getLogger(TestClient.class);
  private final FeatureHubConfig config;
  private final OkHttpClient client = new OkHttpClient();

  public TestClient(FeatureHubConfig config) {
    this.config = config;
  }

  @Override
  public @NotNull TestApiResult setFeatureState(String apiKey, @NotNull String featureKey, @NotNull FeatureStateUpdate featureStateUpdate) {
    String data;

    try {
      data =
        ((InternalFeatureRepository)config.getRepository()).getJsonObjectMapper().writeValueAsString(featureStateUpdate);
    } catch (JsonProcessingException e) {
      return new TestApiResult(500);
    }

    String url = String.format("%s/%s/%s", config.baseUrl(), apiKey, featureKey);

    log.trace("test-url: {}", url);

    Request.Builder reqBuilder =
      new Request.Builder()
        .url(url)
        .post(RequestBody.create(data, MediaType.get("application/json")))
        .addHeader("X-SDK", SdkVersion.sdkVersionHeader("Java-OKHTTP"));

    try(Response response = client.newCall(reqBuilder.build()).execute()) {
      return new TestApiResult(response.code());
    } catch (IOException e) {
      return new TestApiResult(500);
    }
  }

  @Override
  public @NotNull TestApiResult setFeatureState(@NotNull String featureKey, @NotNull FeatureStateUpdate featureStateUpdate) {
    return setFeatureState(config.apiKey(), featureKey, featureStateUpdate);
  }

  @Override
  public void close() {
    client.dispatcher().executorService().shutdown();
  }
}
