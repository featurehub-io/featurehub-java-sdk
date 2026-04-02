package io.featurehub.sdk.usageadapter.opentelemetry;

import io.featurehub.client.ExtendedFeatureValueInterceptor;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.utils.Conversion;
import io.featurehub.sse.model.FeatureState;
import io.opentelemetry.api.baggage.Baggage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.TreeMap;

/**
 * An {@link ExtendedFeatureValueInterceptor} that reads feature overrides from the
 * OpenTelemetry Baggage entry {@value BAGGAGE_KEY}.
 *
 * <p>The baggage value is a comma-separated list of {@code feature=url-encoded-value} pairs
 * kept in alphabetical order by feature key, e.g.:
 * <pre>
 *   dark-mode=true,page-size=20,theme=light%20blue
 * </pre>
 *
 * <p>Locked features are not overridden unless {@code allowLockedOverride} is set (or the
 * environment variable {@value ALLOW_LOCKED_OVERRIDE_ENV} is {@code "true"}).
 */
public class OpenTelemetryFeatureInterceptor implements ExtendedFeatureValueInterceptor {
  private static final Logger log = LoggerFactory.getLogger(OpenTelemetryFeatureInterceptor.class);

  static final String BAGGAGE_KEY = "fhub";
  static final String ALLOW_LOCKED_OVERRIDE_ENV = "FEATUREHUB_OTEL_ALLOW_LOCKED_OVERRIDE";

  private final boolean allowLockedOverride;

  /** Uses {@value ALLOW_LOCKED_OVERRIDE_ENV} env var to determine locked-override behaviour. */
  public OpenTelemetryFeatureInterceptor() {
    this((Boolean) null);
  }

  /**
   * Registers this interceptor with the given {@link FeatureHubConfig}.
   * Uses {@value ALLOW_LOCKED_OVERRIDE_ENV} env var to determine locked-override behaviour.
   */
  public OpenTelemetryFeatureInterceptor(FeatureHubConfig config) {
    this(config, null);
  }

  /**
   * Self-Registers this interceptor with the given {@link FeatureHubConfig}.
   *
   * @param allowLockedOverride if non-null, uses this value directly; if null, reads
   *                            {@value ALLOW_LOCKED_OVERRIDE_ENV} (defaults to {@code false}).
   */
  public OpenTelemetryFeatureInterceptor(FeatureHubConfig config, @Nullable Boolean allowLockedOverride) {
    this(allowLockedOverride);
    config.registerValueInterceptor(this);
  }

  /**
   * @param allowLockedOverride if non-null, uses this value directly; if null, reads
   *                            {@value ALLOW_LOCKED_OVERRIDE_ENV} (defaults to {@code false}).
   */
  public OpenTelemetryFeatureInterceptor(@Nullable Boolean allowLockedOverride) {
    this.allowLockedOverride = allowLockedOverride != null
        ? allowLockedOverride
        : "true".equalsIgnoreCase(System.getenv(ALLOW_LOCKED_OVERRIDE_ENV));

    log.info("[featurehubsdk] open telemetry feature interceptor enabled (locked override {})", Boolean.TRUE == allowLockedOverride ? "on" : "off");
  }

  @Override
  public ValueMatch getValue(String key, InternalFeatureRepository repository,
                             @Nullable FeatureState rawFeature) {
    String fhub = Baggage.current().getEntryValue(BAGGAGE_KEY);
    if (fhub == null || fhub.isEmpty()) {
      return new ValueMatch(false, null);
    }

    TreeMap<String, String> parsed = FhubBaggage.parse(fhub);
    if (!parsed.containsKey(key)) {
      return new ValueMatch(false, null);
    }

    if (!allowLockedOverride && rawFeature != null && Boolean.TRUE.equals(rawFeature.getL())) {
      return new ValueMatch(false, null);
    }

    String decodedValue = FhubBaggage.decode(parsed.get(key));

    log.trace("otel-baggage interceptor: key='{}' decoded='{}'", key, decodedValue);

    return new ValueMatch(true,
        Conversion.toTypedValue(rawFeature == null ? null : rawFeature.getType(),
            decodedValue, key, repository));
  }
}
