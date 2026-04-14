package io.featurehub.client;

import io.featurehub.sse.model.FeatureState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface RawUpdateFeatureListener {
  void updateFeatures(@NotNull List<FeatureState> features, @NotNull String source);
  void updateFeature(@NotNull FeatureState feature, @NotNull String source);
  void deleteFeature(@NotNull FeatureState feature, @NotNull String source);
  void close();
}
