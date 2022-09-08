package io.featurehub.client.jersey;

import cd.connect.openapi.support.ApiClient;
import io.featurehub.client.EdgeService;
import io.featurehub.client.Feature;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.FeatureStore;
import io.featurehub.client.Readyness;
import io.featurehub.client.utils.SdkVersion;
import io.featurehub.sse.api.FeatureService;
import io.featurehub.sse.model.FeatureStateUpdate;
import io.featurehub.sse.model.SSEResultState;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.sse.EventInput;
import org.glassfish.jersey.media.sse.InboundEvent;
import org.glassfish.jersey.media.sse.SseFeature;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Singleton
@Deprecated
public class JerseyClient implements EdgeService {
  private static final Logger log = LoggerFactory.getLogger(JerseyClient.class);
  private final WebTarget target;
  private boolean initialized;
  private final Executor executor;
  private final FeatureStore repository;
  private final FeatureService featuresService;
  private boolean shutdown = false;
  private boolean shutdownOnServerFailure = true;
  private boolean shutdownOnEdgeFailureConnection = false;
  private EventInput eventInput;
  private String xFeaturehubHeader;
  protected final FeatureHubConfig fhConfig;
  private List<CompletableFuture<Readyness>> waitingClients = new ArrayList<>();

  // only for testing
  private boolean neverConnect = false;

  public JerseyClient(FeatureHubConfig config, FeatureStore repository) {
    this(config, !config.isServerEvaluation(), repository, null);
  }

  public JerseyClient(FeatureHubConfig config, boolean initializeOnConstruction,
                      FeatureStore repository, ApiClient apiClient) {
    this.repository = repository;
    this.fhConfig = config;

    log.trace("new jersey client created");

    repository.setServerEvaluation(config.isServerEvaluation());

    Client client = ClientBuilder.newBuilder()
      .register(JacksonFeature.class)
      .register(SseFeature.class).build();

    target = makeEventSourceTarget(client, config.getRealtimeUrl());
    executor = makeExecutor();

    if (apiClient == null) {
      apiClient = new ApiClient(client, config.baseUrl());
    }

    featuresService = makeFeatureServiceClient(apiClient);

    if (initializeOnConstruction) {
      init();
    }
  }

  protected ExecutorService makeExecutor() {
    // in case they keep changing the context, it will ask the server and cancel and ask and cancel
    // if they are in client mode
    return Executors.newFixedThreadPool(4);
  }

  protected WebTarget makeEventSourceTarget(Client client, String sdkUrl) {
    return client.target(sdkUrl);
  }

  protected FeatureService makeFeatureServiceClient(ApiClient apiClient) {
    return new FeatureServiceImpl(apiClient);
  }

  public void setFeatureState(String key, FeatureStateUpdate update) {
    featuresService.setFeatureState(fhConfig.apiKey(), key, update);
  }

  public void setFeatureState(Feature feature, FeatureStateUpdate update) {
    setFeatureState(feature.name(), update);
  }

  // backoff algorithm should be configurable
  private void avoidServerDdos() {
    if (request != null) {
      request.active = false;
      request = null;
    }

    try {
      Thread.sleep(10000); // wait 10 seconds
    } catch (InterruptedException ignored) {
    }

    if (!shutdown) {
      executor.execute(this::restartRequest);
    }
  }

  private CurrentRequest request;

  class CurrentRequest {
    public boolean active = true;

    public void listenUntilDead() {
      if (neverConnect) return;

      long start = System.currentTimeMillis();
      try {
        Invocation.Builder request = target.request();

        if (xFeaturehubHeader != null) {
          request = request.header("x-featurehub", xFeaturehubHeader);
        }

        request = request.header("X-SDK", SdkVersion.sdkVersionHeader("Java-Jersey2"));

        eventInput = request
          .get(EventInput.class);

        while (!eventInput.isClosed()) {
          final InboundEvent inboundEvent = eventInput.read();
          initialized = true;

          // we cannot force close the client input, it hangs around and waits for the server
          if (!active) {
            return; // ignore all data from this call, it is no longer active or relevant
          }

          if (shutdown || inboundEvent == null) { // connection has been closed or is shutdown
            break;
          }

          log.trace("notifying of {}", inboundEvent.getName());

          try {
            final SSEResultState state = fromValue(inboundEvent.getName());

            if (state != null && state != SSEResultState.CONFIG) {
              repository.notify(state, inboundEvent.readData());
            } else if (state == SSEResultState.CONFIG) {

            }

            if (state == SSEResultState.FAILURE || state == SSEResultState.FEATURES) {
              completeReadyness();
            }

            if (state == SSEResultState.FAILURE && shutdownOnServerFailure) {
              log.warn("Failed to connect to FeatureHub Edge on {}, shutting down.", fhConfig.getRealtimeUrl());
              shutdown();
            }
          } catch (Exception e) {
            log.warn("Failed to parse SSE state {}", inboundEvent.getName(), e);
          }
        }
      } catch (Exception e) {
        if (shutdownOnEdgeFailureConnection) {
          log.warn("Edge connection failed, shutting down");
          repository.notify(SSEResultState.FAILURE, null);
          shutdown();
        }
      }

      eventInput = null; // so shutdown doesn't get confused

      initialized = false;

      if (!shutdown) {
        log.trace("connection closed, reconnecting");
        // timeout should be configurable
        if (System.currentTimeMillis() - start < 2000) {
          executor.execute(JerseyClient.this::avoidServerDdos);
        } else {
          // if we have fallen out, try again
          executor.execute(this::listenUntilDead);
        }
      } else {
        completeReadyness(); // ensure we clear everyone out who is waiting

        log.trace("featurehub client shut down");
      }
    }
  }

  protected SSEResultState fromValue(String name) {
    try {
      return SSEResultState.fromValue(name);
    } catch (Exception e) {
      return null; // ok to have unrecognized values
    }
  }

  public boolean isInitialized() {
    return initialized;
  }

  private void restartRequest() {
    log.trace("starting new request");
    if (request != null) {
      request.active = false;
    }

    initialized = false;

    request = new CurrentRequest();
    request.listenUntilDead();
  }

  void init() {
    if (!initialized) {
      executor.execute(this::restartRequest);
    }
  }

  /**
   * Tell the client to shutdown when we next fall off.
   */
  public void shutdown() {
    log.trace("starting shutdown of jersey edge client");
    this.shutdown = true;

    if (request != null) {
      request.active = false;
    }

    if (eventInput != null) {
      eventInput.close();
    }

    if (executor instanceof ExecutorService) {
      ((ExecutorService)executor).shutdownNow();
    }

    log.trace("exiting shutdown of jersey edge client");
  }

  public boolean isShutdownOnServerFailure() {
    return shutdownOnServerFailure;
  }

  public void setShutdownOnServerFailure(boolean shutdownOnServerFailure) {
    this.shutdownOnServerFailure = shutdownOnServerFailure;
  }

  public boolean isShutdownOnEdgeFailureConnection() {
    return shutdownOnEdgeFailureConnection;
  }

  public void setShutdownOnEdgeFailureConnection(boolean shutdownOnEdgeFailureConnection) {
    this.shutdownOnEdgeFailureConnection = shutdownOnEdgeFailureConnection;
  }

  public String getFeaturehubContextHeader() {
    return xFeaturehubHeader;
  }

  @Override
  public @NotNull Future<Readyness> contextChange(String newHeader, String contextSha) {
    final CompletableFuture<Readyness> change = new CompletableFuture<>();

    if (fhConfig.isServerEvaluation() && ((newHeader != null && !newHeader.equals(xFeaturehubHeader)) || !initialized)) {
      xFeaturehubHeader = newHeader;

      waitingClients.add(change);
      executor.execute(this::restartRequest);
    } else {
      change.complete(repository.getReadyness());
    }

    return change;
  }

  private void completeReadyness() {
    List<CompletableFuture<Readyness>> current = waitingClients;
    waitingClients = new ArrayList<>();
    current.forEach(c -> {
      try {
        c.complete(repository.getReadyness());
      } catch (Exception e) {
        log.error("Unable to complete future", e);
      }
    });
  }

  @Override
  public boolean isClientEvaluation() {
    return !fhConfig.isServerEvaluation();
  }

  @Override
  public void close() {
    shutdown();
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return fhConfig;
  }

  @Override
  public boolean isRequiresReplacementOnHeaderChange() {
    return true;
  }

  @Override
  public void poll() {
    // do nothing, its SSE
  }
}
