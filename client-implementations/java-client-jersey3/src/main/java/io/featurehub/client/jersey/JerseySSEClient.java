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

import java.net.ConnectException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class JerseySSEClient implements EdgeService, EdgeReconnector {
  private static final Logger log = LoggerFactory.getLogger(JerseySSEClient.class);
  private final InternalFeatureRepository repository;
  private final FeatureHubConfig config;
  private volatile String xFeaturehubHeader;
  @Nullable private volatile String lastEventId;
  private final EdgeRetryService retryer;
  private volatile EventInput eventSource;
  // guards changes of context (header, last event id, current connection) against the connection thread
  private final Object connectionLock = new Object();
  private final WebTarget target;
  private final List<CompletableFuture<Readiness>> waitingClients = new ArrayList<>();
  private Consumer<EventInput> notify;

  public JerseySSEClient(@Nullable InternalFeatureRepository repository, @NotNull FeatureHubConfig config,
                         @NotNull EdgeRetryService retryer) {
    this.repository = repository == null ? config.getInternalRepository() : repository;
    this.config = config;
    this.retryer = retryer;

    if (config.isServerEvaluation()) {
      log.warn("Jersey SSE client can take up to the read timeout to apply Context attribute changes, it is " +
        "recommended to use the pure SSE client");
    }

    Client client = ClientBuilder.newBuilder()
      .register(JacksonFeature.class)
      .register(SseFeature.class).build();

    client.property(ClientProperties.CONNECT_TIMEOUT, retryer.getServerConnectTimeoutMs());
    client.property(ClientProperties.READ_TIMEOUT, retryer.getServerReadTimeoutMs());

    log.trace("client set connect timeout to {} and read timeout to {}", retryer.getServerConnectTimeoutMs(), retryer.getServerReadTimeoutMs());

    target = makeEventSourceTarget(client, config.getRealtimeUrl());
  }

  @NotNull
  protected WebTarget makeEventSourceTarget(Client client, String sdkUrl) {
    return client.target(sdkUrl);
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, @Nullable String contextSha) {
    if (config.isServerEvaluation() &&
      (
        (newHeader != null && !newHeader.equals(xFeaturehubHeader)) ||
          (xFeaturehubHeader != null && !xFeaturehubHeader.equals(newHeader))
      )) {

      log.warn("[featurehub-sdk] please only use server evaluated keys with SSE with one repository per SSE client.");

      final EventInput oldSource;

      synchronized (connectionLock) {
        xFeaturehubHeader = newHeader;
        // the context has changed, so the server's event stream is no longer the one we were following
        lastEventId = null;
        oldSource = eventSource;
        eventSource = null;
      }

      closeInBackground(oldSource);
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
    closeEventSource();

    // always stop the retryer, even if we are not currently connected, otherwise a pending reconnect will
    // connect again after we have been closed
    retryer.close();
  }

  /**
   * Drops the current connection (if any) without stopping the retryer, so a new connection can be made.
   */
  private void closeEventSource() {
    final EventInput source;

    synchronized (connectionLock) {
      source = eventSource;
      eventSource = null;
    }

    closeInBackground(source);
  }

  /**
   * Closing a connection while another thread is blocked reading it blocks until that read returns (up to the read
   * timeout), so callers other than the connection thread itself close it in the background.
   */
  private static void closeInBackground(@Nullable EventInput source) {
    if (source == null || source.isClosed()) {
      return;
    }

    final Thread closer = new Thread(() -> closeQuietly(source), "featurehub-sse-close");
    closer.setDaemon(true);
    closer.start();
  }

  private static void closeQuietly(@Nullable EventInput source) {
    try {
      if (source != null && !source.isClosed()) {
        source.close();
      }
    } catch (Exception e) {
      log.trace("[featurehub-sdk] failed to close connection", e);
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

    if (lastEventId != null) {
      request = request.header("Last-Event-ID", lastEventId);
    }

    request = request.header("X-SDK", SdkVersion.sdkVersionHeader("Java-Jersey3"));

    log.trace("[featurehub-sdk] connecting to {}", config.getRealtimeUrl());

    return request.get(EventInput.class);
  }

  private void initEventSource() {
    final String header = xFeaturehubHeader;
    final EventInput source;

    try {
      source = makeEventSource();
    } catch (Exception e) {
      onMakeEventSourceException(e);
      return;
    }

    synchronized (connectionLock) {
      if (!Objects.equals(header, xFeaturehubHeader)) {
        // the context changed while we were connecting, so this connection is for the wrong context. The
        // context change will already have scheduled a new connection.
        closeQuietly(source);
        return;
      }

      eventSource = source;
    }

    log.trace("[featurehub-sdk] connected to {}", config.getRealtimeUrl());

    // we have connected, now what to do?
    boolean connectionSaidBye = false;

    boolean interrupted = false;

    while (!source.isClosed() && !retryer.isStopped() && !interrupted) {
      if (notify != null) { // this is for testing
        notify.accept(null);
      }

      @Nullable String data;
      InboundEvent event;

      try {
        event = source.read();

        if (event == null) {
          // read timed out or the server closed the connection, the loop condition decides which
          log.trace("server read returned no event");
          continue;
        }

        data = event.readData();
      } catch (Exception e) {
        // if we are no longer the current connection we were deliberately closed (e.g. by a context change)
        synchronized (connectionLock) {
          if (eventSource != source) {
            interrupted = true;
            continue;
          }

          eventSource = null;
        }

        onMakeEventSourceException(e);
        log.error("failed read", e);
        interrupted = true;
        continue;
      }

      // check and process under the lock so a context change cannot happen part way through, otherwise we could
      // record an id or features for a context that is no longer current
      synchronized (connectionLock) {
        if (eventSource != source) {
          // superseded by a context change, anything we received is for the old context
          break;
        }

        if (event.getId() != null) {
          lastEventId = event.getId();
        }

        connectionSaidBye = processResult(connectionSaidBye, data, event);
      }
    }

    if (retryer.isStopped()) {
      log.trace("[featurehub] event source closed? {} interrupted? {} retryer stopped? {}", source.isClosed(), interrupted, retryer.isStopped());
      if (!source.isClosed()) {
        close();
      }

      checkForUnsatisfactoryConversation();

      notifyWaitingClients();
      return;
    }

    final boolean current;

    synchronized (connectionLock) {
      current = eventSource == source;

      if (current && !interrupted) {
        eventSource = null;
      }
    }

    if (!current) {
      // superseded or closed by someone else, make sure the connection is not left open
      closeQuietly(source);
    } else if (!interrupted) {
      // the server closed the connection on us, so reconnect. Read failures were already handled above.
      retryer.edgeResult(connectionSaidBye ? EdgeConnectionState.SERVER_SAID_BYE :
        EdgeConnectionState.SERVER_WAS_DISCONNECTED, this);
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

      if (log.isTraceEnabled()) {
        log.trace("[featurehub-sdk] decode packet (state {}) {}:{}", state, event.getName(), data);
      }

      if (state == null) { // unknown state
        return connectionSaidBye;
      }

      if (state == SSEResultState.CONFIG) {
        retryer.edgeConfigInfo(data);
      } else {
        retryer.convertSSEState(state, data, repository, config.getEnvironmentId());

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
      }

      // tell any waiting clients we are now ready
      if (!waitingClients.isEmpty() && (state != SSEResultState.ACK && state != SSEResultState.CONFIG)) {
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
    if (e instanceof ConnectException) {
      retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, this);
    } else if (e instanceof SocketException) {
      retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, this);
    } else if (e instanceof WebApplicationException) {
      WebApplicationException wae = (WebApplicationException) e;
      final Response response = wae.getResponse();
      if (response != null && response.getStatusInfo().getFamily() == Response.Status.Family.CLIENT_ERROR) {
        retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, this);
      } else if (response != null && (response.getStatusInfo().getStatusCode() == 403 || response.getStatusInfo().getStatusCode() == 401)) {
        retryer.edgeResult(EdgeConnectionState.FAILURE, this);
      } else {
        retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, this);
      }
    } else {
      retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, this);
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
  public long currentInterval() {
    return 0;
  }

  @Override
  public void reconnect() {
    poll();
  }

  public void setNotify(Consumer<EventInput> notify) {
    this.notify = notify;
  }
}
