package io.featurehub.client.usage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UsageFeaturesCollectionContext extends UsageFeaturesCollection {
  @NotNull
  Map<String, List<String>> attributes = new HashMap<>();

  public UsageFeaturesCollectionContext(@Nullable String userKey, @Nullable Map<String, Object> additionalParams) {
    super(userKey, additionalParams);
  }

  public UsageFeaturesCollectionContext() {
    super();
  }

  public void setAttributes(Map<String, List<String>> attributes) {
    this.attributes = attributes;
  }

  @Override
  @NotNull public Map<String, Object> toMap() {
    Map<String, Object> m = new HashMap<>(super.toMap());

    m.putAll(attributes);

    return Collections.unmodifiableMap(m);
  }
}
