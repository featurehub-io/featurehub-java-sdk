package io.featurehub.client.interceptor;

import io.featurehub.client.ExtendedFeatureValueInterceptor;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.utils.Conversion;
import io.featurehub.sse.model.FeatureState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Checks system properties for updated features.
 */
public class SystemPropertyValueInterceptor implements ExtendedFeatureValueInterceptor {
  public static final String FEATURE_TOGGLES_PREFIX = "featurehub.feature.";
  public static final String FEATURE_TOGGLES_ALLOW_OVERRIDE = "featurehub.features.allow-override";

  public SystemPropertyValueInterceptor() {
    this(null);
  }

  public SystemPropertyValueInterceptor(@Nullable FeatureHubConfig config) {
    if (config != null) {
      config.registerValueInterceptor(this);
    }
  }

  @Override
  public ValueMatch getValue(String key, InternalFeatureRepository repository, @Nullable FeatureState rawFeature) {
    String value = null;
    boolean matched = false;

    if (System.getProperty(FEATURE_TOGGLES_ALLOW_OVERRIDE) != null) {
      String k = FEATURE_TOGGLES_PREFIX + key;
      if (System.getProperties().containsKey(k)) {
        matched = true;
        value = System.getProperty(k);
        if (value != null && value.trim().isEmpty()) {
          value = null;
        }
      }
    }

    return new ValueMatch(matched, Conversion.toTypedValue(rawFeature == null ? null : rawFeature.getType(), value, key, repository));
  }
}
