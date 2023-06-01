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
import java.util.Objects;
import java.util.UUID;

/**
 * This class is just the base class to avoid a lot of duplication effort and to ensure the
 * maximum performance for each feature in updating its listeners and knowing what type it is.
 */
public class FeatureStateBase<K> implements FeatureState<K> {
  private static final Logger log = LoggerFactory.getLogger(FeatureStateBase.class);
  protected final String key;
  protected io.featurehub.sse.model.FeatureState _featureState;
  List<FeatureListener> listeners = new ArrayList<>();
  protected BaseClientContext context;
  protected FeatureStateBase<K> parentHolder;
  protected final InternalFeatureRepository repository;

  public FeatureStateBase(
    InternalFeatureRepository repository,
    FeatureStateBase<K> parentHolder, String key) {
    this(repository, key);
    this.parentHolder = parentHolder;
  }

  public FeatureStateBase(InternalFeatureRepository repository, String key) {
    this.key = key;
    this.repository = repository;
  }

  public FeatureStateBase<K> withContext(BaseClientContext context) {
    final FeatureStateBase<K> copy = _copy();
    copy.context = context;
    return copy;
  }

  @NotNull protected FeatureStateBase<K> topFeatureState() {
    if (parentHolder == null) {
      return this;
    }

    return parentHolder.topFeatureState();
  }

  @Nullable
  protected io.featurehub.sse.model.FeatureState featureState() {
    // clones for analytics will set the feature state
    if (_featureState != null) {
      return _featureState;
    }

    // child objects for contexts will use this
    if (parentHolder != null) {
      return parentHolder.featureState();
    }

    // otherwise it isn't set
    return null;
  }

  protected void notifyListeners() {
    listeners.forEach((sl) -> repository.execute(() -> sl.notify(this)));
  }

  public String getId() {
    io.featurehub.sse.model.FeatureState fs = featureState();
    return (fs == null) ? "" : fs.getId().toString();
  }

  @Override
  public @NotNull String getKey() {
    return key;
  }

  @Override
  public boolean isLocked() {
    final io.featurehub.sse.model.FeatureState featureState = this.featureState();
    return featureState != null && featureState.getL() == Boolean.TRUE;
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
  public FeatureValueType getType() {
    io.featurehub.sse.model.FeatureState fs = featureState();

    return (fs == null) ? null : fs.getType();
  }

  public Object getAnalyticsFreeValue() {
    return internalGetValue(null, false);
  }

  @Override
  public K getValue(Class<K> clazz) {
    return clazz.cast(internalGetValue(null, true));
  }

  private Object internalGetValue(@Nullable FeatureValueType passedType, boolean triggerUsage) {
    final io.featurehub.sse.model.FeatureState featureState = featureState();

    boolean locked = featureState != null && Boolean.TRUE.equals(featureState.getL());

    // unlike js, locking is registered on a per-interceptor basis
    FeatureValueInterceptor.ValueMatch vm = repository.findIntercept(locked, key);

    if (vm != null) {
      return vm.value;
    }

    if (featureState == null || ( passedType == null && featureState.getType() == null )) {
      return null;
    }

    final FeatureValueType type = passedType == null ? featureState.getType() : passedType;

    if (featureState.getType() != type) {
      return null;
    }

    if (context != null) {
      final Applied applied =
        repository.applyFeature(
          featureState.getStrategies(), key, featureState.getId().toString(), context);

      if (applied.isMatched()) {
        return triggerUsage ? used(key, featureState.getId(), applied.getValue(), type) : applied.getValue();
      }
    }

    return triggerUsage ? used(key, featureState.getId(), featureState.getValue(), type) : featureState.getValue();
  }

  Object used(@NotNull String key, @NotNull UUID id, @Nullable Object value, @NotNull FeatureValueType type) {
    if (context != null) {
      context.used(key, id, value, type);
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
    if (featureState == null) return this;
    Object oldValue = getValue(type());
    this._featureState = featureState;
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
    final FeatureStateBase<K> aCopy = _copy();
    aCopy._featureState = featureState();
    return aCopy;
  }

  protected FeatureStateBase<K> _copy() {
    final FeatureStateBase<K> copy = new FeatureStateBase<>(repository, this, key);
    copy.parentHolder = this;
    return copy;
  }

  public boolean exists() {
    final io.featurehub.sse.model.FeatureState featureState = featureState();
    return featureState != null && featureState.getVersion() != null && featureState.getVersion() != -1;
  }

  protected FeatureValueType type() {
    final io.featurehub.sse.model.FeatureState featureState = featureState();
    return featureState == null ? null : featureState.getType();
  }

  @Override
  public String toString() {
    Object value = getValue(type());
    return value == null ? null : value.toString();
  }
}
