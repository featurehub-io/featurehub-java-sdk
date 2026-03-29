package io.featurehub.client;

import io.featurehub.client.usage.FeatureHubUsageValue;
import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsageFeaturesCollection;
import io.featurehub.client.usage.UsageProvider;
import io.featurehub.javascript.JavascriptObjectMapper;
import io.featurehub.javascript.JavascriptServiceLoader;
import io.featurehub.sse.model.FeatureRolloutStrategy;
import io.featurehub.sse.model.SSEResultState;
import io.featurehub.strategies.matchers.MatcherRegistry;
import io.featurehub.strategies.percentage.PercentageMumurCalculator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientFeatureRepository implements InternalFeatureRepository {
  private static class Callback<T> implements RepositoryEventHandler {
    private final List<Callback<T>> handlers;
    public final Consumer<T> callback;

    public Callback(List<Callback<T>> handlers, Consumer<T> callback) {
      this.handlers = handlers;
      this.handlers.add(this);
      this.callback = callback;
    }

    @Override
    public void cancel() {
      this.handlers.remove(this);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(ClientFeatureRepository.class);
  // feature-key, feature-state
  private final Map<String, FeatureStateBase<?>> features = new ConcurrentHashMap<>();
  private final Map<UUID, FeatureStateBase<?>> featuresById = new ConcurrentHashMap<>();
  @NotNull private ExecutorService executor;
  private boolean hasReceivedInitialState = false;
  private Readiness readiness = Readiness.NotReady;
  private final List<Callback<Readiness>> readinessListeners = new ArrayList<>();
  private final List<Callback<FeatureRepository>> newStateAvailableHandlers = new ArrayList<>();
  private final List<Callback<FeatureState<?>>> featureUpdateHandlers = new ArrayList<>();
  private final List<FeatureValueInterceptorHolder> featureValueInterceptors = new ArrayList<>();
  private final List<ExtendedFeatureValueInterceptor> extendedFeatureValueInterceptors =
      new ArrayList<>();
  private final List<RawUpdateFeatureListener> rawUpdateFeatureListeners = new ArrayList<>();
  private final List<Callback<UsageEvent>> usageHandlers = new ArrayList<>();
  private UsageProvider usageProvider = new UsageProvider.DefaultUsageProvider();

  private JavascriptObjectMapper jsonConfigObjectMapper;
  private final ApplyFeature applyFeature;

  public ClientFeatureRepository(ExecutorService executor, ApplyFeature applyFeature) {
    jsonConfigObjectMapper = JavascriptServiceLoader.load();

    this.executor = executor;

    this.applyFeature =
        applyFeature == null
            ? new ApplyFeature(new PercentageMumurCalculator(), new MatcherRegistry())
            : applyFeature;
  }

  public ClientFeatureRepository(int threadPoolSize) {
    this(getExecutor(threadPoolSize), null);
  }

  public ClientFeatureRepository() {
    this(1);
  }

  public ClientFeatureRepository(ExecutorService executor) {
    this(executor == null ? getExecutor(1) : executor, null);
  }

  protected static ExecutorService getExecutor(int threadPoolSize) {
    int maxThreads = Math.max(threadPoolSize, 10);
    return new ThreadPoolExecutor(
        3,
        maxThreads,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        new FeatureHubThreadFactory());
  }

  public void setJsonConfigObjectMapper(@NotNull JavascriptObjectMapper jsonConfigObjectMapper) {
    this.jsonConfigObjectMapper = jsonConfigObjectMapper;
  }

  public @NotNull Readiness getReadyness() {
    return getReadiness();
  }

  @Override
  public @NotNull Readiness getReadiness() {
    return readiness;
  }

  @Override
  public @NotNull FeatureRepository registerValueInterceptor(
      boolean allowFeatureOverride, @NotNull FeatureValueInterceptor interceptor) {
    featureValueInterceptors.add(
        new FeatureValueInterceptorHolder(allowFeatureOverride, interceptor));

    return this;
  }

  @Override
  public @NotNull FeatureRepository registerValueInterceptor(
      @NotNull ExtendedFeatureValueInterceptor interceptor) {
    extendedFeatureValueInterceptors.add(interceptor);
    return this;
  }

  @Override
  public @NotNull FeatureRepository registerRawUpdateFeatureListener(
      @NotNull RawUpdateFeatureListener listener) {
    rawUpdateFeatureListeners.add(listener);
    return this;
  }

  @Override
  public void registerUsageProvider(@NotNull UsageProvider provider) {
    this.usageProvider = provider;
  }

  @Override
  public @NotNull RepositoryEventHandler registerNewFeatureStateAvailable(
      @NotNull Consumer<FeatureRepository> callback) {
    return new Callback<>(newStateAvailableHandlers, callback);
  }

  @Override
  public @NotNull RepositoryEventHandler registerFeatureUpdateAvailable(
      @NotNull Consumer<FeatureState<?>> callback) {
    return new Callback<>(featureUpdateHandlers, callback);
  }

  @Override
  public @NotNull RepositoryEventHandler registerUsageStream(
      @NotNull Consumer<UsageEvent> callback) {
    return new Callback<>(usageHandlers, callback);
  }

  @Override
  public void notify(@NotNull SSEResultState state, @NotNull String source) {
    log.trace("received state {}", state);
    try {
      switch (state) {
        case ACK:
        case BYE:
        case DELETE_FEATURE:
        case FEATURE:
        case FEATURES:
          break;
        case FAILURE:
          readiness = Readiness.Failed;
          broadcastReadyness();
          break;
      }
    } catch (Exception e) {
      log.error("Unable to process state `{}`", state, e);
    }
  }

  @Override
  public void updateFeatures(
      @NotNull List<io.featurehub.sse.model.FeatureState> features, @NotNull String source) {
    updateFeatures(features, false, source);
  }

  @Override
  public void updateFeatures(
      List<io.featurehub.sse.model.FeatureState> states, boolean force, @NotNull String source) {
    log.trace("received {} features from {}", features.size(), source);
    states.forEach(s -> updateFeatureInternal(s, force, source));
    rawUpdateFeatureListeners.forEach(l -> execute(() -> l.updateFeatures(states, source)));

    if (!hasReceivedInitialState) {
      hasReceivedInitialState = true;
      readiness = Readiness.Ready;
      broadcastInitialStateToUsage(states);
      broadcastReadyness();
    } else if (readiness != Readiness.Ready) {
      readiness = Readiness.Ready;
      broadcastReadyness();
    }
  }

  protected void broadcastInitialStateToUsage(List<io.featurehub.sse.model.FeatureState> states) {
    if (!usageHandlers.isEmpty()) {
      final UsageFeaturesCollection uce = usageProvider.createUsageCollectionEvent();
      uce.setFeatureValues(
          states.stream()
              .map(
                  fs -> {
                    final EvaluatedFeature result =
                        getFeat(fs.getKey()).internalGetValue(null, false);
                    return result == null ? null : new FeatureHubUsageValue(result);
                  })
              .filter(Objects::nonNull)
              .collect(Collectors.toList()));
      recordUsageEvent(uce);
    }
  }

  @Override
  public @NotNull Applied applyFeature(
      @NotNull List<FeatureRolloutStrategy> strategies,
      @NotNull String key,
      @NotNull String featureValueId,
      @NotNull ClientContext cac) {
    return applyFeature.applyFeature(strategies, key, featureValueId, cac);
  }

  @Override
  public void execute(@NotNull Runnable command) {
    if (!executor.isShutdown()) {
      executor.execute(command);
    }
  }

  @Override
  public ExecutorService getExecutor() {
    return executor;
  }

  @Override
  public @NotNull JavascriptObjectMapper getJsonObjectMapper() {
    return jsonConfigObjectMapper;
  }

  @Override
  public void repositoryNotReady() {
    readiness = Readiness.NotReady;
    broadcastReadyness();
  }

  @Override
  public void close() {
    log.info("featurehub repository closing");
    extendedFeatureValueInterceptors.forEach(ExtendedFeatureValueInterceptor::close);
    rawUpdateFeatureListeners.forEach(RawUpdateFeatureListener::close);
    features.clear();

    readiness = Readiness.NotReady;
    readinessListeners.forEach(rl -> rl.callback.accept(readiness));
    readinessListeners.clear();

    executor.shutdownNow();

    log.info("featurehub repository closed");
  }

  @Override
  public @NotNull RepositoryEventHandler addReadinessListener(@NotNull Consumer<Readiness> rl) {
    final Callback<Readiness> callback = new Callback<>(readinessListeners, rl);

    if (!executor.isShutdown()) {
      // let it know what the current state is
      executor.execute(() -> rl.accept(readiness));
    }

    return callback;
  }

  private void broadcastReadyness() {
    log.trace("broadcasting readiness {} listener count {}", readiness, readinessListeners.size());
    if (!executor.isShutdown()) {
      readinessListeners.forEach((rl) -> executor.execute(() -> rl.callback.accept(readiness)));
    }
  }

  @Override
  public void deleteFeature(
      @NotNull io.featurehub.sse.model.FeatureState readValue, @NotNull String source) {
    log.trace("received delete feature {} from {}", readValue.getKey(), source);

    final FeatureStateBase<?> holder = features.remove(readValue.getKey());
    if (readValue.getId() != null) {
      featuresById.remove(readValue.getId());
    }
    if (holder != null) {
      holder.setFeatureState(null);
      broadcastFeatureUpdatedListeners(holder);
    }
    rawUpdateFeatureListeners.forEach(l -> execute(() -> l.deleteFeature(readValue, source)));
  }

  @Override
  public @NotNull List<FeatureState<?>> getAllFeatures() {
    return new ArrayList<>(features.values());
  }

  @Override
  public @NotNull Set<String> getFeatureKeys() {
    return features.keySet();
  }

  @Override
  public @NotNull FeatureState<?> feature(String key) {
    return getFeat(key);
  }

  @Override
  public @NotNull <K> FeatureState<K> feature(String key, Class<K> clazz) {
    return getFeat(key, clazz);
  }

  @Override
  public boolean updateFeature(
      @NotNull io.featurehub.sse.model.FeatureState featureState, @NotNull String source) {
    boolean changed = updateFeatureInternal(featureState, false, source);
    rawUpdateFeatureListeners.forEach(l -> execute(() -> l.updateFeature(featureState, source)));
    return changed;
  }

  @Override
  public boolean updateFeature(
      @NotNull io.featurehub.sse.model.FeatureState featureState,
      boolean force,
      @NotNull String source) {
    log.trace("received update feature {} from {}", featureState.getKey(), source);
    boolean changed = updateFeatureInternal(featureState, force, source);
    rawUpdateFeatureListeners.forEach(l -> execute(() -> l.updateFeature(featureState, source)));
    return changed;
  }

  private boolean updateFeatureInternal(
      @NotNull io.featurehub.sse.model.FeatureState featureState,
      boolean force,
      @NotNull String source) {
    FeatureStateBase<?> holder = features.get(featureState.getKey());
    if (holder == null) {
      holder = new FeatureStateBase<>(this, featureState.getKey());

      features.put(featureState.getKey(), holder);
    } else if (holder.feature.fs != null && !force) {
      long existingVersion =
          holder.feature.fs.getVersion() == null ? -1 : holder.feature.fs.getVersion();
      long newVersion = featureState.getVersion() == null ? -1 : featureState.getVersion();
      if (existingVersion > newVersion
          || (newVersion == existingVersion
              && !FeatureStateUtils.changed(
                  holder.feature.fs.getValue(), featureState.getValue()))) {
        // if the old existingVersion is newer, or they are the same existingVersion and the value
        // hasn't changed.
        // it can change with server side evaluation based on user data
        return false;
      }
    }

    holder.setFeatureState(featureState);
    featuresById.put(featureState.getId(), holder);

    if (hasReceivedInitialState) {
      broadcastFeatureUpdatedListeners(holder);
    }

    return true;
  }

  @NotNull
  public FeatureStateBase<?> getFeat(@NotNull String key) {
    return getFeat(key, Boolean.class);
  }

  @Override
  @NotNull
  public FeatureStateBase<?> getFeat(@NotNull Feature key) {
    return getFeat(key.name(), Boolean.class);
  }

  @Override
  @SuppressWarnings("unchecked") // it is all fake anyway
  @NotNull
  public <K> FeatureStateBase<K> getFeat(@NotNull String key, @NotNull Class<K> clazz) {
    return (FeatureStateBase<K>)
        features.computeIfAbsent(
            key,
            key1 -> {
              if (hasReceivedInitialState) {
                log.warn(
                    "FeatureHub error: application requesting use of invalid key after initialization: `{}`",
                    key1);
              }

              return new FeatureStateBase<K>(this, key);
            });
  }

  private void broadcastFeatureUpdatedListeners(@NotNull FeatureState<?> fs) {
    featureUpdateHandlers.forEach((handler) -> execute(() -> handler.callback.accept(fs)));
  }

  @Override
  public void recordUsageEvent(@NotNull UsageEvent event) {
    usageHandlers.forEach(handler -> execute(() -> handler.callback.accept(event)));
  }

  @Override
  public void repositoryEmpty() {
    readiness = Readiness.Ready;
    broadcastReadyness();
  }

  @Override
  public void used(
      EvaluatedFeature value,
      @Nullable Map<String, List<String>> attributes,
      String usageUserKey) {

    recordUsageEvent(
        usageProvider.createUsageFeature(
            new FeatureHubUsageValue(value), attributes, usageUserKey));
  }

  @Override
  public @Nullable ExtendedFeatureValueInterceptor.ValueMatch findIntercept(
      @NotNull String key, io.featurehub.sse.model.@Nullable FeatureState featureState) {
    final ExtendedFeatureValueInterceptor.ValueMatch matched =
        extendedFeatureValueInterceptors.stream()
            .map(
                fv -> {
                  return fv.getValue(key, this, featureState);
                })
            .filter(Objects::nonNull)
            .filter(r -> r.matched)
            .findFirst()
            .orElse(new ExtendedFeatureValueInterceptor.ValueMatch(false, null));

    if (matched.matched) {
      return matched;
    }

    return ExtendedFeatureValueInterceptor.ValueMatch.fromOld(
        findIntercept(featureState != null && featureState.getL(), key));
  }

  @Override
  public FeatureValueInterceptor.ValueMatch findIntercept(boolean locked, @NotNull String key) {
    return featureValueInterceptors.stream()
        .filter(vi -> !locked || vi.allowLockOverride)
        .map(
            vi -> {
              FeatureValueInterceptor.ValueMatch vm = vi.interceptor.getValue(key);
              if (vm != null && vm.matched) {
                return vm;
              } else {
                return null;
              }
            })
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  @Override
  public @NotNull UsageProvider getUsageProvider() {
    return usageProvider;
  }
}
