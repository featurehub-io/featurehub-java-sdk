package todo.backend;

import io.featurehub.client.ClientContext;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.sdk.usageadapter.segment.SegmentAnalyticsSource;

import java.util.concurrent.Future;

public interface FeatureHub {
  FeatureHubConfig getConfig();
  SegmentAnalyticsSource segmentAnalytics();
}
