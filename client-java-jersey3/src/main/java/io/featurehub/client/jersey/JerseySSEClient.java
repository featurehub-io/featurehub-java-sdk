package io.featurehub.client.jersey;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.client.edge.EdgeConnectionState;
import io.featurehub.client.edge.EdgeReconnector;
import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.utils.SdkVersion;
import io.featurehub.sse.model.SSEResultState;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.sse.EventInput;
import org.glassfish.jersey.media.sse.InboundEvent;
import org.glassfish.jersey.media.sse.SseFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class JerseySSEClient implements EdgeService, EdgeReconnector {
  private static final Logger log = LoggerFactory.getLogger(JerseySSEClient.class);
  private final InternalFeatureRepository repository;
  private final FeatureHubConfig config;
  private String xFeaturehubHeader;
  private final EdgeRetryService retryer;
  private EventInput eventSource;
  private final WebTarget target;
  private final List<CompletableFuture<Readiness>> waitingClients = new ArrayList<>();
  private Consumer<EventInput> notify;

  public JerseySSEClient(@Nullable InternalFeatureRepository repository, @NotNull FeatureHubConfig config,
                         @NotNull EdgeRetryService retryer) {
    this.repository = repository == null ? (InternalFeatureRepository) config.getRepository() : repository;
    this.config = config;
    this.retryer = retryer;

    if (config.isServerEvaluation()) {
      log.warn("Jersey SSE client hangs on Context attribute changes for up to 30 seconds, it is recommending using " +
        "the pure SSE client");
    }

    Client client = ClientBuilder.newBuilder()
      .register(JacksonFeature.class)
      .register(SseFeature.class).build();

    client.property(ClientProperties.CONNECT_TIMEOUT, retryer.getServerConnectTimeoutMs());
    client.property(ClientProperties.READ_TIMEOUT,    retryer.getServerConnectTimeoutMs());

    target = makeEventSourceTarget(client, config.getRealtimeUrl());
  }

  @NotNull protected WebTarget makeEventSourceTarget(Client client, String sdkUrl) {
    return client.target(sdkUrl);
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, @Nullable String contextSha) {
    if (config.isServerEvaluation() &&
      (
        (newHeader != null && !newHeader.equals(xFeaturehubHeader)) ||
          (xFeaturehubHeader != null && !xFeaturehubHeader.equals(newHeader))
      ) ) {

      log.warn("[featurehub-sdk] please only use server evaluated keys with SSE with one repository per SSE client.");

      xFeaturehubHeader = newHeader;

      close();
    }

    if (eventSource == null) {
      return poll();
    }

    return CompletableFuture.completedFuture(repository.getReadiness());
  }

  @Override
  public boolean isClientEvaluation() {
    return !config.isServerEvaluation();
  }

  @Override
  public boolean isStopped() {
    return retryer.isStopped();
  }

  @Override
  public void close() {
    if (eventSource != null) {
      if (!eventSource.isClosed()) {
        eventSource.close();
      }

      eventSource = null;
    }
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  protected EventInput makeEventSource() {
    Invocation.Builder request = target.request();

    if (xFeaturehubHeader != null) {
      request = request.header("x-featurehub", xFeaturehubHeader);
    }

    request = request.header("X-SDK", SdkVersion.sdkVersionHeader("Java-Jersey2"));

    log.trace("[featurehub-sdk] connecting to {}", config.getRealtimeUrl());

    return request.get(EventInput.class);
  }

  private void initEventSource() {
    try {
      eventSource = makeEventSource();
    } catch (Exception e) {
      onMakeEventSourceException(e);
      return;
    }

    log.trace("[featurehub-sdk] connected to {}", config.getRealtimeUrl());

    // we have connected, now what to do?
    boolean connectionSaidBye = false;

    boolean interrupted = false;

    while (!eventSource.isClosed() && !interrupted) {
      if (notify != null) { // this is for testing
        notify.accept(null);
      }

      @Nullable String data;
      InboundEvent event;

      try {
        event = eventSource.read();

        if (event == null) {
          interrupted = true;
          continue;
        }
        data = event.readData();
      } catch (Exception e) {
        log.error("failed read", e);
        interrupted = true;
        continue;
      }

      connectionSaidBye = processResult(connectionSaidBye, data, event);
    }

    if (retryer.isStopped() || eventSource.isClosed() || interrupted) {
      final boolean closedOrInterrupted = eventSource.isClosed() || interrupted;

      log.trace("[featurehub] closed");
      if (!eventSource.isClosed()) {
        close();
      }

      checkForUnsatisfactoryConversation();

      notifyWaitingClients();

     if (closedOrInterrupted) {
       // send this once we are actually disconnected and not before
       retryer.edgeResult(connectionSaidBye ? EdgeConnectionState.SERVER_SAID_BYE :
         EdgeConnectionState.SERVER_WAS_DISCONNECTED, this);
     }
    }
  }

  private void checkForUnsatisfactoryConversation() {
    // we never received a satisfactory connection
    if (repository.getReadiness() == Readiness.NotReady) {
      repository.notify(SSEResultState.FAILURE);
    }
  }

  private boolean processResult(boolean connectionSaidBye, String data, InboundEvent event) {
    try {
      final SSEResultState state = retryer.fromValue(event.getName());

      if (state == null) { // unknown state
        return connectionSaidBye;
      }

      log.trace("[featurehub-sdk] decode packet {}:{}", event.getName(), data);

      if (state == SSEResultState.CONFIG) {
        retryer.edgeConfigInfo(data);
      } else {
        retryer.convertSSEState(state, data, repository);
      }

      // reset the timer
      if (state == SSEResultState.FEATURES) {
        retryer.edgeResult(EdgeConnectionState.SUCCESS, this);
      }

      if (state == SSEResultState.BYE) {
        connectionSaidBye = true;
      }

      if (state == SSEResultState.FAILURE) {
        retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, this);
      }

      // tell any waiting clients we are now ready
      if (!waitingClients.isEmpty() && (state != SSEResultState.ACK && state != SSEResultState.CONFIG) ) {
        notifyWaitingClients();
      }
    } catch (Exception e) {
      log.error("[featurehub-sdk] failed to decode packet {}:{}", event.getName(), data, e);
    }

    return connectionSaidBye;
  }

  private void notifyWaitingClients() {
    waitingClients.forEach(wc -> wc.complete(repository.getReadiness()));
  }

  private void onMakeEventSourceException(Exception e) {
    log.info("[featurehub-sdk] failed to connect to {}", config.getRealtimeUrl());
    if (e instanceof WebApplicationException) {
      WebApplicationException wae = (WebApplicationException) e;
      final Response response = wae.getResponse();
      if (response != null && response.getStatusInfo().getFamily() == Response.Status.Family.CLIENT_ERROR) {
        retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, this);
      } else {
        retryer.edgeResult(EdgeConnectionState.SERVER_CONNECT_TIMEOUT, this);
      }
    } else {
      retryer.edgeResult(EdgeConnectionState.SERVER_CONNECT_TIMEOUT, this);
    }
  }

  @Override
  public Future<Readiness> poll() {
    if (eventSource == null) {
      final CompletableFuture<Readiness> change = new CompletableFuture<>();

      waitingClients.add(change);

      retryer.getExecutorService().submit(this::initEventSource);

      return change;
    }

    return CompletableFuture.completedFuture(repository.getReadiness());
  }

  @Override
  public void reconnect() {
    poll();
  }

  public void setNotify(Consumer<EventInput> notify) {
    this.notify = notify;
  }
}
