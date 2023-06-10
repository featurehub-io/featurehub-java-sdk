package io.featurehub.migrationcheck;

import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.edge.EdgeRetryer;
import io.featurehub.okhttp.RestClient;
import io.featurehub.client.EdgeFeatureHubConfig;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.Readiness;
import io.featurehub.edge.sse.SSEClientFactory;
import io.featurehub.okhttp.SSEClient;
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

    // now we _directly_ create the REST based client, pointing it at our config and our repository
    RestClient client = new RestClient(config);

    // and now we block, waiting for it to connect and tell us if it is ready or not
    if (client.contextChange(null, "0").get() == Readiness.Ready) {
      client.close(); // make sure you close it, it has a background thread
      // once it is ready, we tell the config to use SSE as its connector, and start the config going.
      config.setEdgeService(() -> new SSEClient(config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build()));
      config.init();

      System.out.println("ready and waiting for updates via SSE");

      System.in.read();
    } else {
      System.out.println("unable to become ready");
      client.close();  // make sure you close it, it has a background thread
    }
  }
}
