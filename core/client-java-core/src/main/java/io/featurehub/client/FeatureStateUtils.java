package io.featurehub.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FeatureStateUtils {

  static boolean changed(Object oldValue, Object newValue) {
    return ((oldValue != null && newValue == null) || (newValue != null && oldValue == null) ||
      (oldValue != null && !oldValue.equals(newValue)) || (newValue != null && !newValue.equals(oldValue)));
  }

  public static String generateXFeatureHubHeaderFromMap(Map<String, List<String>> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return null;
    }

    return attributes.entrySet().stream().map(e -> String.format("%s=%s", e.getKey(),
     URLEncoder.encode(String.join(",", e.getValue()), StandardCharsets.UTF_8))).sorted().collect(Collectors.joining(","));
  }
}
