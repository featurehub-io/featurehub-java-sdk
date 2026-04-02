package todo.backend;

import cd.connect.lifecycle.ApplicationLifecycleManager;
import cd.connect.lifecycle.LifecycleStatus;
import com.segment.analytics.messages.Message;
import io.featurehub.client.EdgeFeatureHubConfig;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.interceptor.SystemPropertyValueInterceptor;
import io.featurehub.client.jersey.JerseyFeatureHubClientFactory;
import io.featurehub.okhttp.OkHttpFeatureHubFactory;
import io.featurehub.sdk.redis.RedisSessionStore;
import io.featurehub.sdk.redis.RedisSessionStoreOptions;
import io.featurehub.sdk.usageadapter.opentelemetry.OpenTelemetryFeatureInterceptor;
import io.featurehub.sdk.usageadapter.opentelemetry.OpenTelemetryUsagePlugin;
import io.featurehub.sdk.usageadapter.segment.SegmentAnalyticsSource;
import io.featurehub.sdk.usageadapter.segment.SegmentMessageTransformer;
import io.featurehub.sdk.usageadapter.segment.SegmentUsagePlugin;
import io.featurehub.sdk.yaml.LocalYamlFeatureStore;
import io.featurehub.sdk.yaml.LocalYamlValueInterceptor;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.JedisPool;

public class FeatureHubSource implements FeatureHub {
  String featureHubUrl = FeatureHubConfig.getRequiredConfig("feature-service.host");
  String sdkKey = FeatureHubConfig.getRequiredConfig("feature-service.api-key");
  String segmentWriteKey = FeatureHubConfig.getConfig("segment.write-key");
  String client = FeatureHubConfig.getConfig("feature-service.client", "sse"); // sse, rest, rest-poll
  Boolean openTelemetryEnabled = Boolean.parseBoolean(FeatureHubConfig.getConfig("feature-service.opentelemetry.enabled", "false"));
  Integer pollInterval = Integer.parseInt(FeatureHubConfig.getConfig("feature-service.poll-interval-seconds", "1")); // in seconds
  Boolean useOkHttp = FeatureHubConfig.getConfig("featurehub.client", "jersey").equalsIgnoreCase("okhttp");

  @Nullable SegmentAnalyticsSource segmentAnalyticsSource;

  private final FeatureHubConfig config;

  public FeatureHubSource() {
    if (System.getenv("FEATUREHUB_LOCAL_YAML") != null) {
      config = new EdgeFeatureHubConfig();
      new LocalYamlValueInterceptor(config, null, true);
      // registers itself
      new LocalYamlFeatureStore(config);
    } else {
      config = new EdgeFeatureHubConfig(featureHubUrl, sdkKey);

      if (useOkHttp) {
        config.setEdgeSupplierFactory(new OkHttpFeatureHubFactory());
      } else {
        config.setEdgeSupplierFactory(new JerseyFeatureHubClientFactory());
      }

      new SystemPropertyValueInterceptor(config);

      if (System.getenv("REDIS_URL") != null) {
        new RedisSessionStore(new JedisPool(System.getenv("REDIS_URL")), config, RedisSessionStoreOptions.builder().refreshTimeoutSeconds(5).build());
      }
    }

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
      // forces flags not to be overwritten and honours the ones in the baggage
      config.registerValueInterceptor(new OpenTelemetryFeatureInterceptor());
    }

    // support the docker e2e test standard
    if (System.getenv("FEATUREHUB_POLLING_INTERVAL") != null) {
      if (System.getenv("FEATUREHUB_POLLING_PASSIVE") == null) {
        config.restPassive(Integer.parseInt(System.getenv("FEATUREHUB_POLLING_INTERVAL")));
      } else {
        config.restActive(Integer.parseInt(System.getenv("FEATUREHUB_POLLING_INTERVAL")));
      }
    } else {
      if (client.equals("rest")) {
        config.restPassive(pollInterval);
      } else if (client.equals("rest-poll")) {
        config.restActive(pollInterval);
      } else {
        config.streaming();
      }
    }

    config.init();

    ApplicationLifecycleManager.registerListener(trans -> {
      if (trans.next == LifecycleStatus.TERMINATING) {
        close();
      }
    });
  }

  // used if you only want to listen to Redis for updates for example
  public void disconnectEdge() {
    // force edge to close so we stop listening for updates from FeatureHub and only get them from a local source
    // e.g. redis or whatever
    config.closeEdge();
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
