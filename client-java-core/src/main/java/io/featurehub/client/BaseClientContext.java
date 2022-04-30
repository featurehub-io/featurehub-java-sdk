package io.featurehub.client;

import io.featurehub.sse.model.StrategyAttributeCountryName;
import io.featurehub.sse.model.StrategyAttributeDeviceName;
import io.featurehub.sse.model.StrategyAttributePlatformName;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public abstract class BaseClientContext implements ClientContext {
  public static final String USER_KEY = "userkey";
  public static final String SESSION_KEY = "session";
  public static final String COUNTRY_KEY = "country";
  public static final String DEVICE_KEY = "device";
  public static final String PLATFORM_KEY = "platform";
  public static final String VERSION_KEY = "version";
  public static final String C_ID = "cid";
  protected final Map<String, List<String>> clientContext = new ConcurrentHashMap<>();
  protected final FeatureRepositoryContext repository;
  protected final FeatureHubConfig config;

  public BaseClientContext(FeatureRepositoryContext repository, FeatureHubConfig config) {
    this.repository = repository;
    this.config = config;
  }

  @Override
  public String get(String key, String defaultValue) {
    if (clientContext.containsKey(key)) {
      final List<String> vals = clientContext.get(key);
      return vals.isEmpty() ? defaultValue : vals.get(0);
    }

    return defaultValue;
  }

  @Override
  public ClientContext userKey(String userKey) {
    clientContext.put(USER_KEY, Collections.singletonList(userKey));
    return this;
  }

  @Override
  public ClientContext sessionKey(String sessionKey) {
    clientContext.put(SESSION_KEY, Collections.singletonList(sessionKey));
    return this;
  }

  @Override
  public ClientContext country(StrategyAttributeCountryName countryName) {
    clientContext.put(COUNTRY_KEY, Collections.singletonList(countryName.toString()));
    return this;
  }

  @Override
  public ClientContext device(StrategyAttributeDeviceName deviceName) {
    clientContext.put(DEVICE_KEY, Collections.singletonList(deviceName.toString()));
    return this;
  }

  @Override
  public ClientContext platform(StrategyAttributePlatformName platformName) {
    clientContext.put(PLATFORM_KEY, Collections.singletonList(platformName.toString()));
    return this;
  }

  @Override
  public ClientContext version(String version) {
    clientContext.put(VERSION_KEY, Collections.singletonList(version));
    return this;
  }

  @Override
  public ClientContext attr(String name, String value) {
    clientContext.put(name, Collections.singletonList(value));
    return this;
  }

  @Override
  public ClientContext attrs(String name, List<String> values) {
    clientContext.put(name, values);
    return this;
  }

  @Override
  public ClientContext clear() {
    clientContext.clear();
    return this;
  }

  @Override
  public Map<String, List<String>> context() {
    return clientContext;
  }

  @Override
  public String defaultPercentageKey() {
    if (clientContext.containsKey(SESSION_KEY)) {
      return clientContext.get(SESSION_KEY).get(0);
    }
    if (clientContext.containsKey(USER_KEY)) {
      return clientContext.get(USER_KEY).get(0);
    }

    return null;
  }

  @Override
  public FeatureState feature(String name) {
    final FeatureState fs = getRepository().getFeatureState(name);

    return getRepository().isServerEvaluation() ? fs : fs.withContext(this);
  }

  @Override
  public List<FeatureState> allFeatures() {
    boolean isServerEvaluation = getRepository().isServerEvaluation();
    return getRepository().getAllFeatures().stream()
      .map(f -> isServerEvaluation ? f : f.withContext(this))
      .collect(Collectors.toList());
  }

  @Override
  public FeatureState feature(Feature name) {
    return feature(name.name());
  }

  @Override
  public boolean isEnabled(Feature name) {
    return isEnabled(name.name());
  }

  @Override
  public boolean isEnabled(String name) {
    // we use this mechanism as it will return the state within the context (vs repository which might be different)
    return feature(name).isEnabled();
  }

  @Override
  public boolean isSet(Feature name) {
    return isSet(name.name());
  }

  @Override
  public boolean isSet(String name) {
    // we use this mechanism as it will return the state within the context (vs repository which might be different)
    return feature(name).isSet();
  }

  @Override
  public boolean exists(Feature key) {
    return exists(key.name());
  }


  @Override
  public FeatureRepository getRepository() {
    return repository;
  }

  @Override
  public boolean exists(String key) {
    return repository.exists(key);
  }

  @Override
  public ClientContext logAnalyticsEvent(String action, Map<String, String> other) {
    String user = get(USER_KEY, null);

    if (user != null) {
      if (other == null) {
        other = new HashMap<>();
      }

      if (!other.containsKey(C_ID)) {
        other.put(C_ID, user);
      }
    }

    repository.logAnalyticsEvent(action, other, this);

    return this;
  }

  @Override
  public ClientContext logAnalyticsEvent(String action) {
    return logAnalyticsEvent(action, null);
  }

}
