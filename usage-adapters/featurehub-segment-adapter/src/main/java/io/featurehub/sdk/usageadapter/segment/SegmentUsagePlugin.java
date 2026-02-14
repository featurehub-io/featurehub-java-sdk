package io.featurehub.sdk.usageadapter.segment;

import com.segment.analytics.Analytics;
import com.segment.analytics.MessageTransformer;
import com.segment.analytics.messages.TrackMessage;
import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsageEventName;
import io.featurehub.client.usage.UsagePlugin;
import okhttp3.OkHttpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The Segment Usage Adapter is used when you wish to track "feature" events - so when a feature is evaluated
 */
public class SegmentUsagePlugin extends UsagePlugin implements SegmentAnalyticsSource {
  final Analytics analytics;
  private static final Logger log = LoggerFactory.getLogger(SegmentUsagePlugin.class);

  public SegmentUsagePlugin(String segmentKey) {
    analytics = Analytics.builder(segmentKey).build();
  }

  public SegmentUsagePlugin(@NotNull String segmentKey, @Nullable List<MessageTransformer> segmentMessageTransformer) {
    this(segmentKey, null, segmentMessageTransformer);
  }

  /**
   * Use this constructor if you wish to provide your own OkHttpClient with proxies, timeouts and so forth.
   *
   * @param segmentKey   - the segment write key for a java source
   * @param okHttpClient - an okhttp client configured for use
   */
  public SegmentUsagePlugin(String segmentKey, @Nullable OkHttpClient okHttpClient, @Nullable List<MessageTransformer> segmentMessageTransformer) {
    final Analytics.Builder builder = Analytics.builder(segmentKey);

    if (okHttpClient != null) {
      builder.client(okHttpClient);
    }

    if (segmentMessageTransformer != null) {
      segmentMessageTransformer.forEach(builder::messageTransformer);
    }

    analytics = builder.build();
  }

  /**
   * Use this constructor if you want/need to create your own Analytics object.
   *
   * @param analytics - the provided analytics object.
   */
  public SegmentUsagePlugin(Analytics analytics) {
    this.analytics = analytics;
  }

  /**
   * This constructor assumes the segment write key is an environment variable `FEATUREHUB_SEGMENT_WRITE_KEY`
   * or a system property `featurehub.segment-write-key`. It will construct the analytics object directly with the key
   * and all other settings being default.
   */

  public SegmentUsagePlugin() {
    this(Analytics.builder(segmentKey()).build());
  }

  /**
   * Use this function to get the segment write key if you wish to provide your own OkHttpClient but use the standard
   * keys for segment.
   *
   * @return configured segment key or RuntimeException if not found.
   */
  public static String segmentKey() {
    String segmentKey = System.getenv("FEATUREHUB_USAGE_SEGMENT_WRITE_KEY");

    if (segmentKey == null) {
      segmentKey = System.getProperty("featurehub.usage.segment-write-key");

      if (segmentKey == null) {
        throw new RuntimeException("You must initialize with an env var `FEATUREHUB_SEGMENT_KEY` or provide one to the constructor");
      }
    }

    return segmentKey;
  }

  @Override
  public void send(UsageEvent event) {
    if (event instanceof UsageEventName) {
      final String userId = event.getUserKey() == null ? "anonymous" : event.getUserKey();

      log.trace("segment event {} with key {}", ((UsageEventName) event).getEventName(), userId);

      final TrackMessage.Builder builder =
        TrackMessage.builder(((UsageEventName) event).getEventName()).userId(userId).context(event.toMap());

      analytics.enqueue(builder);
    }
  }

  public Analytics getAnalytics() {
    return analytics;
  }
}
