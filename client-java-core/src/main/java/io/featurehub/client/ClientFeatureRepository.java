package io.featurehub.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.featurehub.client.analytics.AnalyticsAdapter;
import io.featurehub.client.analytics.AnalyticsEvent;
import io.featurehub.client.analytics.AnalyticsProvider;
import io.featurehub.client.analytics.FeatureHubAnalyticsValue;
import io.featurehub.sse.model.FeatureRolloutStrategy;
import io.featurehub.sse.model.FeatureValueType;
import io.featurehub.sse.model.SSEResultState;
import io.featurehub.strategies.matchers.MatcherRegistry;
import io.featurehub.strategies.percentage.PercentageMumurCalculator;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
  private final ExecutorService executor;
  private boolean hasReceivedInitialState = false;
  private Readiness readiness = Readiness.NotReady;
  private final List<Callback<Readiness>> readinessListeners = new ArrayList<>();
  private final List<Callback<FeatureRepository>> newStateAvailableHandlers = new ArrayList<>();
  private final List<Callback<FeatureState<?>>> featureUpdateHandlers = new ArrayList<>();
  private final List<FeatureValueInterceptorHolder> featureValueInterceptors = new ArrayList<>();
  private final List<Callback<AnalyticsEvent>> analyticsHandlers = new ArrayList<>();
  private AnalyticsProvider analyticsProvider = new AnalyticsProvider.DefaultAnalyticsProvider();

  private ObjectMapper jsonConfigObjectMapper;
  private final ApplyFeature applyFeature;
  private AnalyticsAdapter analyticsAdapter;
  private boolean serverEvaluation = false; // the client tells us, we pass it out to others

  private final TypeReference<List<io.featurehub.sse.model.FeatureState>> FEATURE_LIST_TYPEDEF =
      new TypeReference<List<io.featurehub.sse.model.FeatureState>>() {};

  public ClientFeatureRepository(ExecutorService executor, ApplyFeature applyFeature) {
    jsonConfigObjectMapper = initializeMapper();

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

  protected ObjectMapper initializeMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    return mapper;
  }

  protected static ExecutorService getExecutor(int threadPoolSize) {
    return Executors.newFixedThreadPool(threadPoolSize);
  }

  public void setJsonConfigObjectMapper(@NotNull ObjectMapper jsonConfigObjectMapper) {
    this.jsonConfigObjectMapper = jsonConfigObjectMapper;
  }

  @Override
  public boolean isServerEvaluation() {
    return serverEvaluation;
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
  public void registerAnalyticsProvider(@NotNull AnalyticsProvider provider) {
    this.analyticsProvider = provider;
  }

  @Override
  public @NotNull RepositoryEventHandler registerNewFeatureStateAvailable(@NotNull Consumer<FeatureRepository> callback) {
    return new Callback<>(newStateAvailableHandlers, callback);
  }

  @Override
  public @NotNull RepositoryEventHandler registerFeatureUpdateAvailable(@NotNull Consumer<FeatureState<?>> callback) {
    return new Callback<>(featureUpdateHandlers, callback);
  }

  @Override
  public @NotNull RepositoryEventHandler registerAnalyticsStream(@NotNull Consumer<AnalyticsEvent> callback) {
    return new Callback<>(analyticsHandlers, callback);
  }

  @Override
  public void notify(SSEResultState state) {
    log.trace("received state {}", state);
    if (state == null) {
      log.warn("Unexpected null state");
    } else {
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
  }

  @Override
  public void updateFeatures(List<io.featurehub.sse.model.FeatureState> features) {

  }

  @Override
  public void updateFeatures(List<io.featurehub.sse.model.FeatureState> states, boolean force) {
    states.forEach(s -> updateFeature(s, force));

    if (!hasReceivedInitialState) {
      hasReceivedInitialState = true;
      readiness = Readiness.Ready;
      broadcastReadyness();
    } else if (readiness != Readiness.Ready) {
      readiness = Readiness.Ready;
      broadcastReadyness();
    }
  }

  @Override
  public Applied applyFeature(
    List<FeatureRolloutStrategy> strategies, String key, String featureValueId, ClientContext cac) {
    return applyFeature.applyFeature(strategies, key, featureValueId, cac);
  }

  @Override
  public void execute(Runnable command) {
    executor.execute(command);
  }

  @Override
  public ObjectMapper getJsonObjectMapper() {
    return jsonConfigObjectMapper;
  }

  @Override
  public void setServerEvaluation(boolean val) {
    this.serverEvaluation = val;
  }

  @Override
  public void repositoryNotReady() {
    readiness = Readiness.NotReady;
    broadcastReadyness();
  }

  @Override
  public void close() {
    log.info("featurehub repository closing");
    features.clear();

    readiness = Readiness.NotReady;
    readinessListeners.forEach(rl -> rl.callback.accept(readiness));

    executor.shutdownNow();

    log.info("featurehub repository closed");
  }

  @Override
  public @NotNull RepositoryEventHandler addReadinessListener(@NotNull Consumer<Readiness> rl) {
    final Callback<Readiness> callback = new Callback<>(readinessListeners, rl);
    this.readinessListeners.add(callback);

    if (!executor.isShutdown()) {
      // let it know what the current state is
      executor.execute(() -> rl.accept(readiness));
    }

    return callback;
  }

  private void broadcastReadyness() {
    if (!executor.isShutdown()) {
      readinessListeners.forEach((rl) -> executor.execute(() -> rl.callback.accept(readiness)));
    }
  }

  public void deleteFeature(io.featurehub.sse.model.FeatureState readValue) {
    readValue.setValue(null);
    updateFeature(readValue);
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

  public boolean updateFeature(io.featurehub.sse.model.FeatureState featureState) {
    return updateFeature(featureState, false);
  }

  @Override
  public boolean updateFeature(io.featurehub.sse.model.FeatureState featureState, boolean force) {
    FeatureStateBase<?> holder = features.get(featureState.getKey());
    if (holder == null || holder._featureState == null) {
      holder = new FeatureStateBase<>(this, featureState.getKey());

      features.put(featureState.getKey(), holder);
    } else if (!force) {
      long existingVersion = holder._featureState.getVersion() == null ? -1 : holder._featureState.getVersion();
      long newVersion = featureState.getVersion() == null ? -1 : featureState.getVersion();
      if (existingVersion > newVersion
          || (newVersion == existingVersion
              && !FeatureStateUtils.changed(
                  holder._featureState.getValue(), featureState.getValue()))) {
        // if the old existingVersion is newer, or they are the same existingVersion and the value hasn't changed.
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

  public FeatureStateBase<?> getFeat(String key) {
    return getFeat(key, Boolean.class);
  }

  @Override
  @SuppressWarnings("unchecked") // it is all fake anyway
  public <K> FeatureStateBase<K> getFeat(String key, Class<K> clazz) {
    return (FeatureStateBase<K>) features.computeIfAbsent(
      key,
      key1 -> {
        if (hasReceivedInitialState) {
          log.error(
            "FeatureHub error: application requesting use of invalid key after initialization: `{}`",
            key1);
        }

        return new FeatureStateBase<K>(this, key);
      });
  }

  private void broadcastFeatureUpdatedListeners(FeatureState<?> fs) {
    featureUpdateHandlers.forEach((handler) -> execute(() -> handler.callback.accept(fs)));
  }

  @Override
  public void recordAnalyticsEvent(AnalyticsEvent event) {
    analyticsHandlers.forEach(handler -> execute(() -> handler.callback.accept(event)));
  }

  @Override
  public void repositoryEmpty() {
    readiness = Readiness.Ready;
    broadcastReadyness();
  }

  @Override
  public void used(String key, UUID id, FeatureValueType valueType, Object value, Map<String, List<String>> attributes,
                   String analyticsUserKey) {
    recordAnalyticsEvent(analyticsProvider.createAnalyticsFeature(new FeatureHubAnalyticsValue(id.toString(), key,
      value, valueType
      ), attributes, analyticsUserKey));
  }

  @Override
  public FeatureValueInterceptor.ValueMatch findIntercept(boolean locked, String key) {
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
  public AnalyticsProvider getAnalyticsProvider() {
    return analyticsProvider;
  }
}
