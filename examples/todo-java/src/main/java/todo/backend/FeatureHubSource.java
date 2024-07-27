package todo.backend;

import cd.connect.app.config.ConfigKey;
import cd.connect.app.config.DeclaredConfigResolver;
import cd.connect.lifecycle.ApplicationLifecycleManager;
import cd.connect.lifecycle.LifecycleStatus;
import io.featurehub.client.*;
import io.featurehub.okhttp.RestClient;
import io.featurehub.client.edge.EdgeRetryer;
import io.featurehub.client.interceptor.SystemPropertyValueInterceptor;
import io.featurehub.client.jersey.JerseySSEClient;
import io.featurehub.okhttp.SSEClient;
import io.featurehub.sdk.usageadapter.segment.SegmentUsageAdapter;

public class FeatureHubSource implements FeatureHub {
  @ConfigKey("feature-service.host")
  String featureHubUrl;
  @ConfigKey("feature-service.api-key")
  String sdkKey;
  @ConfigKey("segment.write-key")
  String segmentWriteKey = "";
  @ConfigKey("feature-service.sdk")
  String clientSdk = "jersey3";
  @ConfigKey("feature-service.client")
  String client = "sse"; // sse, rest, rest-poll
  @ConfigKey("feature-service.poll-interval")
  Integer pollInterval = 1000; // in milliseconds

  private final FeatureHubConfig config;

  public FeatureHubSource() {
    DeclaredConfigResolver.resolve(this);

    config = new EdgeFeatureHubConfig(featureHubUrl, sdkKey)
      .registerValueInterceptor(true, new SystemPropertyValueInterceptor());

    if (!segmentWriteKey.isEmpty()) {
      config.registerUsagePlugin(new SegmentUsageAdapter(segmentWriteKey));
    }

    // Do this if you wish to force the connection to stay open.
    if (clientSdk.equals("jersey3")) {
      final JerseySSEClient jerseyClient = new JerseySSEClient(config.getInternalRepository(),
        config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build());
      config.setEdgeService(() -> jerseyClient);
    } else if (clientSdk.equals("android") || clientSdk.equals("rest")) {
      final RestClient client = new RestClient(config, pollInterval);
      config.setEdgeService(() -> client);
    } else if (clientSdk.equals("sse")) {
      final SSEClient client = new SSEClient(config.getInternalRepository(), config,
        EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build());
      config.setEdgeService(() -> client);
    } else {
      throw new RuntimeException("Unknown featurehub client");
    }

    config.init();

    ApplicationLifecycleManager.registerListener(trans -> {
      if (trans.next == LifecycleStatus.TERMINATING) {
        close();
      }
    });
  }

  @Override
  public FeatureHubConfig getConfig() {
    return config;
  }

  public void close() {
    config.close();
  }
}
