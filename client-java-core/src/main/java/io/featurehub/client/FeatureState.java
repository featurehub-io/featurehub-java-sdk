package io.featurehub.client;

import io.featurehub.sse.model.FeatureValueType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

public interface FeatureState<K> {
  /**
   * @return - The key, it is always set, even if this is a feature that doesn't exist in the underlying repository
   */
  @NotNull String getKey();

  @Nullable String getString();

  /**
   * @deprecated recommend now using the getFlag() method
   */
  @Deprecated()
  @Nullable Boolean getBoolean();

  /**
   * use isEnabled() if you want to have true/false regardless
   * @return - true if the feature is a flag, has a value and it is true
   */
  @Nullable Boolean getFlag();

  /**
   * Gets the value raw and tries to make it appear as the type you request, regardless of
   * the underlying type. If it is a boolean and you ask for it as a string, it will still be a bool and
   * will cause a compile error.
   *
   * @param clazz for fake typing
   * @return the determined value (can be overridden by feature value interceptors)
   */
  @Nullable K getValue(Class<K> clazz);

  @Nullable BigDecimal getNumber();

  @Nullable String getRawJson();

  @Nullable <T> T getJson(Class<T> type);

  /**
   * true if the flag is boolean and is true
   */
  boolean isEnabled();

  boolean isSet();

  /**
   * @return Are we dealing with a feature that actually exists in the underlying repository?
   */
  boolean exists();

  boolean isLocked();

  /**
   * Adds a listener to a feature. Do *not* add a listener to a context in server mode, where you are creating
   * lots of contexts as this will lead to a memory leak.
   *
   * @param listener
   */
  void addListener(@NotNull FeatureListener listener);

  @Nullable FeatureValueType getType();
}
