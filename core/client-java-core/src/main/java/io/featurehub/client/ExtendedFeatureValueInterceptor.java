package io.featurehub.client;

import io.featurehub.sse.model.FeatureState;
import org.jetbrains.annotations.Nullable;

/*
** ExtendedFeatureValueInterceptors are given the original feature if it exists and they are responsible
* for checking if it is locked and it is OK to override a locked value.
 */
public interface ExtendedFeatureValueInterceptor {
  class ValueMatch {
    public final boolean matched;
    @Nullable
    public final Object value;
    // if this is true, the value is a raw type, bool, string, float, etc. If it is false, then it is a string
    // and likely comes from the old interceptor type and thus will need to be converted
    public final boolean valueIsOriginal;

    public ValueMatch(boolean matched, @Nullable Object value) {
      this(matched, value, true);
    }

    protected static ValueMatch fromOld(@Nullable FeatureValueInterceptor.ValueMatch old) {
      if (old == null) {
        return new ValueMatch(false, null, true);
      }

      return new ValueMatch(old.matched, old.value, false);
    }

    private ValueMatch(boolean matched, @Nullable Object value, boolean valueIsOriginal) {
      this.matched = matched;
      this.value = value;
      this.valueIsOriginal = valueIsOriginal;
    }
  }

  ValueMatch getValue(String key, FeatureRepository repository, @Nullable FeatureState rawFeature);
}
