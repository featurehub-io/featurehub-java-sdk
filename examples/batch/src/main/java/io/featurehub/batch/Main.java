package io.featurehub.batch;

import io.featurehub.client.ClientContext;
import io.featurehub.client.EdgeFeatureHubConfig;
import io.featurehub.client.FeatureHubConfig;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

public class Main {
  static String env(@NotNull String key, @NotNull String defaultVal) {
    String val = System.getenv(key);
    return (val == null) ? defaultVal : val;
  }

  public static void main(String[] args) throws ExecutionException, InterruptedException, IOException {
    String edgeUrl = env("FEATUREHUB_EDGE_URL", "http://localhost:8085");
    String apiKey = env("FEATUREHUB_CLIENT_API_KEY", "ddd28309-7a5d-4e5a-b060-3f02ddd9e771" +
        "/iHwJ3Nvmpqgpz7HK9L7KDTzf9RSH9Q*WYArdlfMWHi6PjT57K6K1");

    // we need to configure the Config that holds this all together and will swap to SSE once we tell it to
    FeatureHubConfig config = new EdgeFeatureHubConfig(edgeUrl, apiKey);
    ClientContext fhContext = config.newContext();
    // force it to wait for state
    fhContext.build().get();

    if (fhContext.isEnabled("FEATURE_UPPERCASE")) {

    }
  }
}
