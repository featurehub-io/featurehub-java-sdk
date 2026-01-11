package todo.backend;

import cd.connect.app.config.ConfigKey;
import cd.connect.app.config.DeclaredConfigResolver;
import cd.connect.lifecycle.ApplicationLifecycleManager;
import cd.connect.lifecycle.LifecycleStatus;
import com.segment.analytics.messages.Message;
import io.featurehub.client.EdgeFeatureHubConfig;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.interceptor.SystemPropertyValueInterceptor;
import io.featurehub.sdk.usageadapter.opentelemetry.OpenTelemetryUsagePlugin;
import io.featurehub.sdk.usageadapter.segment.SegmentAnalyticsSource;
import io.featurehub.sdk.usageadapter.segment.SegmentMessageTransformer;
import io.featurehub.sdk.usageadapter.segment.SegmentUsagePlugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class FeatureHubSource implements FeatureHub {
  String featureHubUrl = FeatureHubConfig.getRequiredConfig("feature-service.host");
  String sdkKey = FeatureHubConfig.getRequiredConfig("feature-service.api-key");
  String segmentWriteKey = FeatureHubConfig.getConfig("segment.write-key");
  String client = FeatureHubConfig.getConfig("feature-service.client", "sse"); // sse, rest, rest-poll
  @ConfigKey()
  Boolean openTelemetryEnabled = Boolean.parseBoolean(FeatureHubConfig.getConfig("feature-service.opentelemetry.enabled", "false"));
  @ConfigKey()
  Integer pollInterval = Integer.parseInt(FeatureHubConfig.getConfig("feature-service.poll-interval-seconds", "1")); // in seconds

  @Nullable SegmentAnalyticsSource segmentAnalyticsSource;

  private final FeatureHubConfig config;

  public FeatureHubSource() {
    config = new EdgeFeatureHubConfig(featureHubUrl, sdkKey)
      .registerValueInterceptor(true, new SystemPropertyValueInterceptor());

    if (segmentWriteKey != null) {
      final SegmentUsagePlugin segmentUsagePlugin = new SegmentUsagePlugin(segmentWriteKey,
        List.of(new SegmentMessageTransformer(Message.Type.values(),
          FeatureHubClientContextThreadLocal::get, false, true)));
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
    } else if (client.equals("rest")) {
      config.restPassive(pollInterval);
    } else if (client.equals("rest-poll")) {
      config.restActive(pollInterval);
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

  @Override
  public SegmentAnalyticsSource segmentAnalytics() {
    return segmentAnalyticsSource;
  }

  public void close() {
    config.close();
  }
}
