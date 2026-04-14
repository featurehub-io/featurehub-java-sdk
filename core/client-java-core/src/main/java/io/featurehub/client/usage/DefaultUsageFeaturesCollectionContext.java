package io.featurehub.client.usage;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DefaultUsageFeaturesCollectionContext extends DefaultUsageFeaturesCollection implements UsageFeaturesCollectionContext {
  @NotNull
  Map<String, List<String>> attributes = new HashMap<>();

  public DefaultUsageFeaturesCollectionContext(@Nullable String userKey, @Nullable Map<String, Object> additionalParams) {
    super(userKey, additionalParams);
  }

  public DefaultUsageFeaturesCollectionContext() {
    super();
  }

  public void setAttributes(Map<String, List<String>> attributes) {
    this.attributes = attributes;
  }

  @Override
  @NotNull public Map<String, ?> toMap() {
    Map<String, Object> m = new HashMap<>(super.toMap());

    m.putAll(attributes);

    return Collections.unmodifiableMap(m);
  }
}
