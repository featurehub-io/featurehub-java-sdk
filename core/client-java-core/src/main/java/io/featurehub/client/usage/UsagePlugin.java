package io.featurehub.client.usage;

import java.util.HashMap;
import java.util.Map;

abstract public class UsagePlugin {
  protected final Map<String, Object> defaultEventParams = new HashMap<>();
//  protected final boolean unnamedBecomeEventParameters;
//
//  public UsagePlugin(boolean unnamedBecomeEventParameters) {
//    this.unnamedBecomeEventParameters = unnamedBecomeEventParameters;
//  }

  public Map<String, Object> getDefaultEventParams() {
    return defaultEventParams;
  }

  public abstract void send(UsageEvent event);
}
