package io.featurehub.client;

import io.featurehub.client.usage.UsageEvent;
import io.featurehub.sse.model.StrategyAttributeCountryName;
import io.featurehub.sse.model.StrategyAttributeDeviceName;
import io.featurehub.sse.model.StrategyAttributePlatformName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

public interface ClientContext {
  String get(String key, String defaultValue);
  @NotNull List<@NotNull String> getAttrs(String key, @NotNull String defaultValue);
  @Nullable List<String> getAttrs(@NotNull String name);

  ClientContext userKey(String userKey);
  ClientContext sessionKey(String sessionKey);
  ClientContext country(StrategyAttributeCountryName countryName);
  ClientContext device(StrategyAttributeDeviceName deviceName);
  ClientContext platform(StrategyAttributePlatformName platformName);
  ClientContext version(String version);
  ClientContext attr(String name, String value);
  ClientContext attrs(String name, List<String> values);

  ClientContext attrs(Map<String, List<String>> values);
  ClientContext attrsMerge(Map<String, List<String>> values);

  ClientContext clear();

  @Nullable String getAttr(@NotNull String name);
  @Nullable  String getAttr(@NotNull String name, @Nullable  String defaultVal);

  /**
   * Triggers the build and setting of this context.
   *
   * @return this
   */
  Future<ClientContext> build();

  Map<String, List<String>> context();
  String defaultPercentageKey();

  @NotNull FeatureState<?> feature(String name);
  @NotNull FeatureState<?> feature(Feature name);
  @NotNull List<FeatureState<?>> allFeatures();

  @NotNull FeatureRepository getRepository();
  @NotNull EdgeService getEdgeService();

  /**
   * true if it is a boolean feature and is true within this context.
   *
   * @param name
   * @return false if not true or not boolean, true otherwise.
   */
  boolean isEnabled(String name);
  boolean isEnabled(Feature name);

  boolean isSet(String name);
  boolean isSet(Feature name);

  boolean exists(String key);
  boolean exists(Feature key);

  /**
   * If you have a custom usage event you wish to record, add it here. It will capture any associated data from
   * the current context if possible and add it to the analytics event.
   * @param event
   */
  <T extends UsageEvent> void recordUsageEvent(@NotNull T event);

  /**
   * Use this method to set all the fields of your UsageEvent. It will add the user key in,
   * collect all feature values (if a UsageFeaturesCollection) and add in the context attributes (if a UsageFeaturesCollectionContext)
   * @param event -  the vent to fill in
   * @return the filled in collection
   * @param <T> a type that extends UsageEvent
   */
  @NotNull <T extends UsageEvent> T fillUsageCollection(@NotNull T event);

  void close();
}
