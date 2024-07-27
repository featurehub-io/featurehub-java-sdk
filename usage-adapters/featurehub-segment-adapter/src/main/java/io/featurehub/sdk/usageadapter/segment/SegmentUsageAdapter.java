package io.featurehub.sdk.usageadapter.segment;

import com.segment.analytics.Analytics;
import com.segment.analytics.messages.TrackMessage;
import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsageEventName;
import io.featurehub.client.usage.UsagePlugin;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SegmentUsageAdapter extends UsagePlugin {
  final Analytics analytics;
  private static final Logger log = LoggerFactory.getLogger(SegmentUsageAdapter.class);

  public SegmentUsageAdapter(String segmentKey) {
    analytics = Analytics.builder(segmentKey).build();
  }

  public SegmentUsageAdapter(String segmentKey, OkHttpClient okHttpClient) {
    analytics = Analytics.builder(segmentKey).client(okHttpClient).build();
  }

  public SegmentUsageAdapter() {
    final String segmentKey = System.getenv("FEATUREHUB_SEGMENT_KEY");

    if (segmentKey == null) {
      throw new RuntimeException("You must initialize with an env var `FEATUREHUB_SEGMENT_KEY` or provide one to the constructor");
    }

    analytics = Analytics.builder(segmentKey).build();
  }

  @Override
  public void send(UsageEvent event) {
    if (event instanceof UsageEventName) {
      final String userId = event.getUserKey() == null ? "anonymous" : event.getUserKey();

      log.trace("segment event {} with key {}", ((UsageEventName) event).getEventName(), userId);

      final TrackMessage.Builder builder =
        TrackMessage.builder(((UsageEventName) event).getEventName()).userId(userId).properties(event.toMap());

      analytics.enqueue(builder);
    }
  }
}
