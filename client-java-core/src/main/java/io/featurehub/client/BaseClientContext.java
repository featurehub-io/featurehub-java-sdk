package io.featurehub.client;

import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsageFeature;
import io.featurehub.client.usage.UsageFeaturesCollection;
import io.featurehub.client.usage.UsageFeaturesCollectionContext;
import io.featurehub.client.usage.FeatureHubUsageValue;
import io.featurehub.sse.model.FeatureValueType;
import io.featurehub.sse.model.StrategyAttributeCountryName;
import io.featurehub.sse.model.StrategyAttributeDeviceName;
import io.featurehub.sse.model.StrategyAttributePlatformName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

class BaseClientContext implements InternalContext {
  private static final Logger log = LoggerFactory.getLogger(BaseClientContext.class);
  protected final EdgeService edgeService;

  public static final String USER_KEY = "userkey";
  public static final String SESSION_KEY = "session";
  public static final String COUNTRY_KEY = "country";
  public static final String DEVICE_KEY = "device";
  public static final String PLATFORM_KEY = "platform";
  public static final String VERSION_KEY = "version";
  protected final Map<String, List<String>> attributes = new ConcurrentHashMap<>();
  protected final InternalFeatureRepository repository;

  public BaseClientContext(InternalFeatureRepository repository, EdgeService edgeService) {
    this.repository = repository;
    this.edgeService = edgeService;
  }

  @Override
  public EdgeService getEdgeService() {
    return edgeService;
  }

  @Override
  public String get(String key, String defaultValue) {
    if (attributes.containsKey(key)) {
      final List<String> vals = attributes.get(key);
      return vals.isEmpty() ? defaultValue : vals.get(0);
    }

    return defaultValue;
  }

  @Override
  public @NotNull List<@NotNull String> getAttrs(String key, @NotNull String defaultValue) {
    final List<String> attrs = attributes.get(key);
    return attrs == null ? Arrays.asList(defaultValue) : attrs;
  }

  @Override
  public ClientContext userKey(String userKey) {
    attributes.put(USER_KEY, Collections.singletonList(userKey));
    return this;
  }

  @Override
  public ClientContext sessionKey(String sessionKey) {
    attributes.put(SESSION_KEY, Collections.singletonList(sessionKey));
    return this;
  }

  @Override
  public ClientContext country(StrategyAttributeCountryName countryName) {
    attributes.put(COUNTRY_KEY, Collections.singletonList(countryName.toString()));
    return this;
  }

  @Override
  public ClientContext device(StrategyAttributeDeviceName deviceName) {
    attributes.put(DEVICE_KEY, Collections.singletonList(deviceName.toString()));
    return this;
  }

  @Override
  public ClientContext platform(StrategyAttributePlatformName platformName) {
    attributes.put(PLATFORM_KEY, Collections.singletonList(platformName.toString()));
    return this;
  }

  @Override
  public ClientContext version(String version) {
    attributes.put(VERSION_KEY, Collections.singletonList(version));
    return this;
  }

  @Override
  public ClientContext attr(String name, String value) {
    attributes.put(name, Collections.singletonList(value));
    return this;
  }

  @Override
  public ClientContext attrs(String name, List<String> values) {
    attributes.put(name, values);
    return this;
  }

  @Override
  public void used(@NotNull String key, @NotNull UUID id, @Nullable Object val,
                             @NotNull FeatureValueType valueType) {

    repository.execute(() -> {
      try {
        repository.used(key, id, valueType, val, attributes, usageUserKey());
        edgeService.poll().get();
      } catch (Exception e) {
        log.error("Failed to poll", e);
      }
    });
  }

  @Nullable String usageUserKey() {
    return getAttr("session", getAttr("userkey"));
  }


  protected void recordFeatureChangedForUser(FeatureStateBase<?> feature) {
    repository.recordUsageEvent(new UsageFeature(
      new FeatureHubUsageValue(feature.withContext(this)), attributes,
      usageUserKey()));
  }

  protected void recordRelativeValuesForUser() {
    repository.recordUsageEvent(fillUsageCollection(repository.getUsageProvider().createUsageCollectionEvent()));
  }

  protected UsageEvent fillUsageCollection(UsageEvent event) {
    event.setUserKey(usageUserKey());

    if (event instanceof UsageFeaturesCollection) {
      ((UsageFeaturesCollection)event).setFeatureValues(
        repository.getFeatureKeys().stream().map((k) ->
          new FeatureHubUsageValue(repository.getFeat(k))).collect(Collectors.toList()));
    }

    if (event instanceof UsageFeaturesCollectionContext) {
      ((UsageFeaturesCollectionContext)event).setAttributes(attributes);
    }

    return event;
  }

  @Override
  public void recordUsageEvent(@NotNull UsageEvent event) {
    repository.recordUsageEvent(fillUsageCollection(event));
  }

  @Override
  @Nullable public String getAttr(@NotNull String name, @Nullable String defaultVal) {
    String val = getAttr(name);
    return val == null ? defaultVal : val;
  }

  @Override
  @Nullable public String getAttr(@NotNull String name) {
    return attributes.containsKey(name) ? attributes.get(name).get(0) : null;
  }

  @Override
  @Nullable public List<String> getAttrs(@NotNull String name) {
    return attributes.getOrDefault(name, null);
  }

  @Override
  public Future<ClientContext> build() {
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public ClientContext clear() {
    attributes.clear();
    return this;
  }

  @Override
  public Map<String, List<String>> context() {
    return Collections.unmodifiableMap(attributes);
  }

  @Override
  public String defaultPercentageKey() {
    if (attributes.containsKey(SESSION_KEY)) {
      return attributes.get(SESSION_KEY).get(0);
    }
    if (attributes.containsKey(USER_KEY)) {
      return attributes.get(USER_KEY).get(0);
    }

    return null;
  }

  @Override
  public @NotNull FeatureState<?> feature(String name) {
    return repository.getFeat(name).withContext(this);
  }

  @Override
  public @NotNull List<FeatureState<?>> allFeatures() {
    return repository.getFeatureKeys().stream()
      .map(f -> repository.getFeat(f).withContext(this))
      .collect(Collectors.toList());
  }

  @Override
  public @NotNull FeatureState<?> feature(Feature name) {
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
  public @NotNull FeatureRepository getRepository() {
    return repository;
  }

  @Override
  public boolean exists(String key) {
    return feature(key).exists();
  }

  @Override
  public void close() {
    edgeService.close();
  }
}
