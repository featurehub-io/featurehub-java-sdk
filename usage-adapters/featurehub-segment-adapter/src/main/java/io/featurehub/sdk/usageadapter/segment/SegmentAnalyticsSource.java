package io.featurehub.sdk.usageadapter.segment;

import com.segment.analytics.Analytics;

/**
 * If you wish to implement your own plugin
 */
public interface SegmentAnalyticsSource {
  Analytics getAnalytics();
}
