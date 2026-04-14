package io.featurehub.sdk.usageadapter.opentelemetry;

import io.featurehub.client.usage.FeatureHubUsageValue;
import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsageEventWithFeature;
import io.featurehub.client.usage.UsageFeaturesCollection;
import io.featurehub.client.usage.UsagePlugin;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.TreeMap;

/**
 * A {@link UsagePlugin} that propagates evaluated feature values to downstream services by
 * writing them into the OpenTelemetry Baggage under the key {@code fhub}.
 *
 * <p>The {@code fhub} baggage entry is a comma-separated, <em>alphabetically sorted</em> list of
 * {@code feature=url-encoded-value} pairs, compatible with {@link OpenTelemetryFeatureInterceptor}.
 *
 * <p>Two event types are handled:
 * <ul>
 *   <li>{@link UsageEventWithFeature} — merges a single feature into the current {@code fhub}.</li>
 *   <li>{@link UsageFeaturesCollection} — merges all features from
 *       {@link UsageFeaturesCollection#getFeatureValues()} into the current {@code fhub}.</li>
 * </ul>
 *
 * <p>Raw values are used (not the converted forms such as {@code "on"}/{@code "off"} for booleans)
 * to preserve full fidelity for the interceptor. A {@code null} raw value is stored as a key-only
 * entry (no {@code =} sign), which the interceptor converts back to the type's null/default.
 *
 * <p><strong>Context lifecycle note:</strong> {@code send()} must be called synchronously on the
 * request thread. The method updates the thread-local OTel context via {@code makeCurrent()} and
 * intentionally leaves the returned {@link io.opentelemetry.context.Scope} open so the updated
 * baggage remains visible for the rest of the request (including any outgoing HTTP calls where OTel
 * propagation injects it as a header). The outer request context managed by OTel instrumentation
 * (e.g. a Servlet filter or Spring interceptor) will restore the pre-request context when the
 * request ends.
 */
public class OpenTelemetryBaggagePlugin extends UsagePlugin {
  private static final Logger log = LoggerFactory.getLogger(OpenTelemetryBaggagePlugin.class);

  public OpenTelemetryBaggagePlugin() {
    log.info("[featurehubsdk] open telemetry baggage plugin installed");
  }

  @Override
  public void send(UsageEvent event) {
    if (event instanceof UsageEventWithFeature) {
      FeatureHubUsageValue feature = ((UsageEventWithFeature) event).getFeature();
      mergeIntoBaggage(feature.getKey(), feature.getRawValue());

    } else if (event instanceof UsageFeaturesCollection) {
      List<FeatureHubUsageValue> features = ((UsageFeaturesCollection) event).getFeatureValues();
      if (features == null || features.isEmpty()) {
        return;
      }
      mergeIntoBaggage(features);
    }
  }

  private void mergeIntoBaggage(String key, Object rawValue) {
    TreeMap<String, String> current =
        FhubBaggage.parse(Baggage.current().getEntryValue(OpenTelemetryFeatureInterceptor.BAGGAGE_KEY));
    current.put(key, FhubBaggage.encode(rawValue));
    makeCurrent(FhubBaggage.build(current));
  }

  private void mergeIntoBaggage(List<FeatureHubUsageValue> features) {
    TreeMap<String, String> current =
        FhubBaggage.parse(Baggage.current().getEntryValue(OpenTelemetryFeatureInterceptor.BAGGAGE_KEY));
    for (FeatureHubUsageValue fv : features) {
      current.put(fv.getKey(), FhubBaggage.encode(fv.getRawValue()));
    }
    makeCurrent(FhubBaggage.build(current));
  }

  private static void makeCurrent(String fhub) {
    log.trace("otel-baggage plugin: setting fhub='{}'", fhub);
    Baggage newBaggage = Baggage.current().toBuilder()
        .put(OpenTelemetryFeatureInterceptor.BAGGAGE_KEY, fhub)
        .build();
    // Intentionally left open — see class javadoc.
    Context.current().with(newBaggage).makeCurrent();
  }
}
