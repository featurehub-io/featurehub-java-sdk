package io.featurehub.examples.quarkus;

import com.segment.analytics.messages.Message;
import io.featurehub.client.ClientContext;
import io.featurehub.client.EdgeFeatureHubConfig;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.interceptor.SystemPropertyValueInterceptor;
import io.featurehub.sdk.usageadapter.opentelemetry.OpenTelemetryUsagePlugin;
import io.featurehub.sdk.usageadapter.segment.SegmentAnalyticsSource;
import io.featurehub.sdk.usageadapter.segment.SegmentMessageTransformer;
import io.featurehub.sdk.usageadapter.segment.SegmentUsagePlugin;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@Startup
public class FeatureHubSource {
  private static final Logger log = LoggerFactory.getLogger(FeatureHubSource.class);

  @ConfigProperty(name = "feature-service.host")
  String featureHubUrl;
  @ConfigProperty(name = "feature-service.api-key")
  String sdkKey;
  @ConfigProperty(name = "segment.write-key")
  Optional<String> segmentWriteKey;
  @ConfigProperty(name = "feature-service.client", defaultValue = "sse")
  String client; // sse, rest-active, rest-passive
  @ConfigProperty(name = "feature-service.opentelemetry.enabled", defaultValue = "false")
  Boolean openTelemetryEnabled;
  @ConfigProperty(name = "feature-service.poll-interval-seconds", defaultValue = "1")
  Integer pollInterval; // in seconds

  @Produces
  @Nullable SegmentAnalyticsSource segmentAnalyticsSource;

  private FeatureHubConfig config;

  @Produces
  @ApplicationScoped
  @Startup
  public FeatureHubConfig getConfig() {
    if (featureHubUrl == null || sdkKey == null) {
      throw new RuntimeException("URL and Key must not be null");
    }
    log.info("Initializing FeatureHub");
    config = new EdgeFeatureHubConfig(featureHubUrl, sdkKey)
      .registerValueInterceptor(true, new SystemPropertyValueInterceptor());

    if (segmentWriteKey.isPresent()) {
      final SegmentUsagePlugin segmentUsagePlugin = new SegmentUsagePlugin(segmentWriteKey.get(),
        List.of(new SegmentMessageTransformer(Message.Type.values(), () -> {
          final Set<Bean<?>> beans = CDI.current().getBeanManager().getBeans(ClientContext.class);
          return beans.isEmpty() ? null : (ClientContext) beans.iterator().next();
        }, false, true)));
      config.registerUsagePlugin(segmentUsagePlugin);
      segmentAnalyticsSource = segmentUsagePlugin;
    }

    if (openTelemetryEnabled) {
      // this won't do anything if otel isn't found or configured
      config.registerUsagePlugin(new OpenTelemetryUsagePlugin());
    }

    // Do this if you wish to force the connection to stay open.
    if (client.equals("sse")) {
      config.streaming();
    } else if (client.equals("rest-passive")) {
      config.restPassive(pollInterval);
    } else if (client.equals("rest-active")) {
      config.restActive(pollInterval);
    } else {
      throw new RuntimeException("Unknown featurehub client");
    }

    config.init();
    return config;
  }

  /**
   * This lets us create the ClientContext, which will always be empty, or the AuthFilter will add the user if it
   * discovers it.
   *
   * @param config - the FeatureHub Config
   * @return - a blank context usable by any resource.
   */
  @Produces
  @RequestScoped
  public ClientContext fhClient(FeatureHubConfig config) {
    try {
      return config.newContext().build().get();
    } catch (Exception e) {
      log.error("Cannot create context!", e);
      throw new RuntimeException(e);
    }
  }

  @PreDestroy
  public void close() {
    config.close();
  }
}
