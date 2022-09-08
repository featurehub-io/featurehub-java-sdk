package todo.backend;

import cd.connect.app.config.ConfigKey;
import cd.connect.app.config.DeclaredConfigResolver;
import io.featurehub.android.FeatureHubClient;
import io.featurehub.client.ClientContext;
import io.featurehub.client.ClientFeatureRepository;
import io.featurehub.client.EdgeFeatureHubConfig;
import io.featurehub.client.FeatureRepositoryContext;
import io.featurehub.client.GoogleAnalyticsCollector;
import io.featurehub.client.edge.EdgeRetryer;
import io.featurehub.client.interceptor.SystemPropertyValueInterceptor;
import io.featurehub.client.jersey.GoogleAnalyticsJerseyApiClient;
import io.featurehub.client.jersey.JerseySSEClient;
import io.featurehub.edge.sse.SSEClient;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;

public class FeatureHubSource implements FeatureHub {
  @ConfigKey("feature-service.host")
  String featureHubUrl;
  @ConfigKey("feature-service.api-key")
  String sdkKey;
  @ConfigKey("feature-service.google-analytics-key")
  String analyticsKey = "";
  @ConfigKey("feature-service.cid")
  String analyticsCid = "";
  @ConfigKey("feature-service.sdk")
  String clientSdk = "jersey3";

  private final FeatureRepositoryContext repository;
  private final EdgeFeatureHubConfig config;
  @Nullable
  private final FeatureHubClient androidClient;

  public FeatureHubSource() {
    DeclaredConfigResolver.resolve(this);

    config = new EdgeFeatureHubConfig(featureHubUrl, sdkKey);

    repository = new ClientFeatureRepository(5);
    repository.registerValueInterceptor(true, new SystemPropertyValueInterceptor());

    if (analyticsCid.length() > 0 && analyticsKey.length() > 0) {
      repository.addAnalyticCollector(new GoogleAnalyticsCollector(analyticsKey, analyticsCid,
        new GoogleAnalyticsJerseyApiClient()));
    }

    config.setRepository(repository);

    // Do this if you wish to force the connection to stay open.
    if (clientSdk.equals("jersey3")) {
      final JerseySSEClient jerseyClient = new JerseySSEClient(repository,
        config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build());
      config.setEdgeService(() -> jerseyClient);
      androidClient = null;
    } else if (clientSdk.equals("android")) {
      final FeatureHubClient client = new FeatureHubClient(featureHubUrl, Collections.singleton(sdkKey), repository,
        config, 1);
      config.setEdgeService(() -> client);
      androidClient = client;
    } else if (clientSdk.equals("sse")) {
      final SSEClient client = new SSEClient(repository, config,
        EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build());
      config.setEdgeService(() -> client);
      androidClient = null;
    } else {
      throw new RuntimeException("Unknown featurehub client");
    }

    config.init();
  }

  public ClientContext fhClient() {
    return config.newContext();
  }

  @Override
  public FeatureRepositoryContext getRepository() {
    return repository;
  }

  @Override
  public void poll() {
    if (androidClient != null) {
      androidClient.poll();
    }
  }
}
