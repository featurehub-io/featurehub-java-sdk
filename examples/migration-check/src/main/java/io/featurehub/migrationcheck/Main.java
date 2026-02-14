package io.featurehub.migrationcheck;

import io.featurehub.client.EdgeFeatureHubConfig;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.Readiness;
import io.featurehub.client.edge.EdgeRetryer;
import io.featurehub.okhttp.RestClient;
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
    String apiKey = env("FEATUREHUB_CLIENT_API_KEY", "08c8a5f3-f766-4059-98cf-581424c8a6e3/fYetsNTQlWR7rTq9vPQv6bNd2i6W6o*5aHEEjnyIjNo2QmCnuEj");

    // we need to configure the Config that holds this all together and will swap to SSE once we tell it to
    FeatureHubConfig config = new EdgeFeatureHubConfig(edgeUrl, apiKey);

    // now we _directly_ create the REST based client, pointing it at our config and our repository
    RestClient client = new RestClient(config);

    // and now we block, waiting for it to connect and tell us if it is ready or not
    if (client.poll().get() == Readiness.Ready) {
      client.close(); // make sure you close it, it has a background thread
      // once it is ready, we tell the config to use SSE as its connector, and start the config going.
      config.setEdgeService(() -> new SSEClient(config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().sse().build()));
      config.init();

      System.out.println("ready and waiting for updates via SSE");

      System.in.read();
    } else {
      System.out.println("unable to become ready");
      client.close();  // make sure you close it, it has a background thread
    }
  }
}
