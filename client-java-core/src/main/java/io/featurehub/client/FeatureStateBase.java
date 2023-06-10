package io.featurehub.client;

import io.featurehub.sse.model.FeatureValueType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This class is just the base class to avoid a lot of duplication effort and to ensure the
 * maximum performance for each feature in updating its listeners and knowing what type it is.
 */
public class FeatureStateBase<K> implements FeatureState<K> {
  private static final Logger log = LoggerFactory.getLogger(FeatureStateBase.class);
  protected final TopFeatureState feature;
  protected final FeatureStateBase<K> top;
  protected final List<FeatureListener> listeners;
  protected InternalContext context;
  protected FeatureStateBase<K> parentHolder;
  protected final InternalFeatureRepository repository;

  // any levels of the hierarchy always point to this object
  class TopFeatureState {
    public io.featurehub.sse.model.FeatureState fs;
    public String key;  // we always keep this in case the state gets reset to null

    public TopFeatureState(String key) {
      this.key = key;
    }
  }

  // if this is a child
  public FeatureStateBase(
    @NotNull InternalFeatureRepository repository,
    @NotNull FeatureStateBase<K> parentHolder) {
    this.repository = repository;
    this.parentHolder = parentHolder;
    feature = parentHolder.feature;

    top = top();
    listeners = top.listeners;
  }

  // this is exclusively for internal analytic copying
  protected FeatureStateBase(@NotNull InternalFeatureRepository repository, @NotNull String key,
                             @Nullable io.featurehub.sse.model.FeatureState featureState) {
    this.repository = repository;
    this.parentHolder = null;
    this.feature = new TopFeatureState(key);
    this.feature.fs = featureState;
    top = this;
    this.listeners = new ArrayList<>();
  }

  // this is for a new FeatureStateBase
  public FeatureStateBase(@NotNull InternalFeatureRepository repository, String key) {
    this.repository = repository;
    this.feature = new TopFeatureState(key);
    top = this;
    this.listeners = new ArrayList<>();
  }

  public FeatureStateBase<K> withContext(InternalContext context) {
    final FeatureStateBase<K> copy = _copy();
    copy.context = context;
    return copy;
  }

  // should only be used in constructor and is set once
  @NotNull protected FeatureStateBase<K> top() {
    if (parentHolder == null) {
      return this;
    }

    return parentHolder.top();
  }

  protected void notifyListeners() {
    listeners.forEach((sl) -> repository.execute(() -> sl.notify(this)));
  }

  public String getId() {
    return (feature.fs == null) ? "" : feature.fs.getId().toString();
  }

  @Override
  public @NotNull String getKey() {
    return feature.fs == null ? feature.key : feature.fs.getKey();
  }

  @Override
  public boolean isLocked() {
    return feature.fs != null && feature.fs.getL() == Boolean.TRUE;
  }

  @Override
  public String getString() {
    return getAsString(FeatureValueType.STRING);
  }

  @Override
  @Deprecated
  public Boolean getBoolean() {
    return getFlag();
  }

  @Override
  public Boolean getFlag() {
    Object val = getValue(FeatureValueType.BOOLEAN);

    if (val == null) {
      return null;
    }

    if (val instanceof String) {
      return Boolean.TRUE.equals("true".equalsIgnoreCase(val.toString()));
    }

    return Boolean.TRUE.equals(val);
  }

  @Nullable
  private Object getValue(@Nullable FeatureValueType type) {
    return internalGetValue(type, true);
  }

  @Override
  @Nullable public FeatureValueType getType() {
    return (feature.fs == null) ? null : feature.fs.getType();
  }

  public Object getAnalyticsFreeValue() {
    return internalGetValue(null, false);
  }

  @Override
  public K getValue(Class<K> clazz) {
    return clazz.cast(internalGetValue(null, true));
  }

  private Object internalGetValue(@Nullable FeatureValueType passedType, boolean triggerUsage) {
    boolean locked = feature.fs != null && Boolean.TRUE.equals(feature.fs.getL());

    // unlike js, locking is registered on a per-interceptor basis
    FeatureValueInterceptor.ValueMatch vm = repository.findIntercept(locked, feature.key);

    if (vm != null) {
      return vm.value;
    }

    if (feature.fs == null || ( passedType == null && feature.fs.getType() == null )) {
      return null;
    }

    final FeatureValueType type = passedType == null ? feature.fs.getType() : passedType;

    if (feature.fs.getType() != type) {
      return null;
    }

    if (context != null && feature.fs.getStrategies() != null && !feature.fs.getStrategies().isEmpty()) {
      final Applied applied =
        repository.applyFeature(
          feature.fs.getStrategies(), feature.key, feature.fs.getId().toString(), context);

      log.trace("feature is {}", applied);
      if (applied.isMatched()) {
        return triggerUsage ? used(feature.key, feature.fs.getId(), applied.getValue(), type) : applied.getValue();
      }
    } else {
      log.trace("not matched using {}", feature.fs.getValue());
    }

    return triggerUsage ? used(feature.key, feature.fs.getId(), feature.fs.getValue(), type) :
      feature.fs.getValue();
  }

  Object used(@NotNull String key, @NotNull UUID id, @Nullable Object value, @NotNull FeatureValueType type) {
    if (context != null) {
      context.used(key, id, value, type);
    } else {
      log.trace("calling used with  {}", value);
      repository.used(key, id, type, value, null, null);
    }

    return value;
  }

  private String getAsString(FeatureValueType type) {
    Object value = getValue(type);
    return value == null ? null : value.toString();
  }

  @Override
  public BigDecimal getNumber() {
    Object val = getValue(FeatureValueType.NUMBER);

    try {
      return (val == null) ? null : (val instanceof BigDecimal ? ((BigDecimal)val) : new BigDecimal(val.toString()));
    } catch (Exception e) {
      log.warn("Attempting to convert {} to BigDecimal fails as is not a number", val);
      return null; // ignore conversion failures
    }
  }

  @Override
  public String getRawJson() {
    return getAsString(FeatureValueType.JSON);
  }

  @Override
  public <T> T getJson(Class<T> type) {
    String rawJson = getRawJson();

    try {
      return rawJson == null ? null : repository.getJsonObjectMapper().readValue(rawJson, type);
    } catch (IOException e) {
      log.warn("Failed to parse JSON", e);
      return null;
    }
  }

  @Override
  public boolean isEnabled() {
    return getFlag() == Boolean.TRUE;
  }

  @Override
  public boolean isSet() {
    return getValue((FeatureValueType) null) != null;
  }


  @Override
  public void addListener(final @NotNull FeatureListener listener) {
    if (context != null) {
      listeners.add((fs) -> listener.notify(this));
    } else {
      listeners.add(listener);
    }
  }

  // stores the feature state and triggers notifyListeners if anything changed
  // should notify actually be inside the listener code? given contexts?
  public FeatureState<K> setFeatureState(io.featurehub.sse.model.FeatureState featureState) {
    if (featureState == null) {
      boolean changed = feature.fs != null;
      feature.fs = featureState;
      if (changed) {
        notifyListeners();
      }
      return this;
    }

    feature.key = featureState.getKey();

    Object oldValue = feature.fs == null ? null : feature.fs.getValue();
    feature.fs = featureState;
    Object value = convertToRespectiveType(featureState);
    if (FeatureStateUtils.changed(oldValue, value)) {
      notifyListeners();
    }
    return this;
  }

  @Nullable
  private Object convertToRespectiveType(io.featurehub.sse.model.FeatureState featureState) {
    if (featureState.getValue() == null || featureState.getType() == null) {
      return null;
    }

    try {
      switch (featureState.getType()) {
        case BOOLEAN:
          return Boolean.parseBoolean(featureState.getValue().toString());
        case STRING:
        case JSON:
          return featureState.getValue().toString();
        case NUMBER:
          return new BigDecimal(featureState.getValue().toString());
      }
    } catch (Exception ignored) {
    }

    return null;
  }

  protected FeatureState<K> copy() {
    return _copy();
  }

  protected FeatureState<K> analyticsCopy() {
    return new FeatureStateBase<K>(repository, feature.key, feature.fs);
  }

  protected FeatureStateBase<K> _copy() {
    return new FeatureStateBase<>(repository, this);
  }

  public boolean exists() {
    return feature.fs != null && feature.fs.getVersion() != null && feature.fs.getVersion() != -1;
  }

  protected FeatureValueType type() {
    return feature.fs == null ? null : feature.fs.getType();
  }

  @Override
  public String toString() {
    Object value = feature.fs == null ? null : feature.fs.getValue();
    return value == null ? null : value.toString();
  }
}
