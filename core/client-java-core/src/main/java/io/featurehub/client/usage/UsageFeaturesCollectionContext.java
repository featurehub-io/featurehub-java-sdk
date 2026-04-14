package io.featurehub.client.usage;

import java.util.List;
import java.util.Map;

public interface UsageFeaturesCollectionContext extends UsageFeaturesCollection {
  void setAttributes(Map<String, List<String>> attributes);
}
