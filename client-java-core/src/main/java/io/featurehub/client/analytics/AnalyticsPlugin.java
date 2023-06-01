package io.featurehub.client.analytics;

import java.util.HashMap;
import java.util.Map;

abstract public class AnalyticsPlugin {
  protected final Map<String, Object> defaultEventParams = new HashMap<>();
  protected final boolean unnamedBecomeEventParameters;

  public AnalyticsPlugin(boolean unnamedBecomeEventParameters) {
    this.unnamedBecomeEventParameters = unnamedBecomeEventParameters;
  }

  abstract void send(AnalyticsEvent event);
}
