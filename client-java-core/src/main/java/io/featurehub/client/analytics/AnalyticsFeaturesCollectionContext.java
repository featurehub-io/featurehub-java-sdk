package io.featurehub.client.analytics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnalyticsFeaturesCollectionContext extends AnalyticsFeaturesCollection {
  @NotNull
  Map<String, List<String>> attributes = new HashMap<>();

  public AnalyticsFeaturesCollectionContext(@Nullable String userKey, @NotNull Map<String, Object> additionalParams) {
    super(userKey, additionalParams);
  }

  public AnalyticsFeaturesCollectionContext() {
    super();
  }

  public void setAttributes(Map<String, List<String>> attributes) {
    this.attributes = attributes;
  }

  @Override
  @NotNull Map<String, Object> toMap() {
    Map<String, Object> m = new HashMap<>(super.toMap());

    m.putAll(attributes);

    return Collections.unmodifiableMap(m);
  }
}
