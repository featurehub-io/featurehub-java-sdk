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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
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
  // a connection has been asked for and is waiting to run or is connecting, so asking again would make a duplicate.
  // Guarded by connectionLock.
  private boolean connectPending;
  // once closed we never connect again. Set under connectionLock.
  private volatile boolean closed;
  private final Client client;
  private final WebTarget target;
  // has the current connection sent us features (or told us it can't) yet? Until it has, callers must wait. Guarded
  // by connectionLock.
  private boolean connectionReady;
  // added to by callers' threads and drained by the connection thread. Guarded by connectionLock.
  private final List<CompletableFuture<Readiness>> waitingClients = new ArrayList<>();
  // closing a connection can block for up to the read timeout (see closeInBackground), so it is done by a shared pool
  // of daemon threads. Closes must not queue behind each other, or old connections stay open long after being replaced.
  private static final ExecutorService closer = makeCloser();
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

    client = ClientBuilder.newBuilder()
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
    final CompletableFuture<Readiness> change = new CompletableFuture<>();
    final boolean contextChanged;
    EventInput oldSource = null;
    boolean connect = false;

    // done in one step, so a connection made for the new context can't complete between us dropping the old one and
    // deciding whether to wait (and so tell the caller we are ready before any of its features have arrived)
    synchronized (connectionLock) {
      if (closed || retryer.isStopped()) {
        // we will never connect again, so no new features are coming
        change.complete(repository.getReadiness());
        return change;
      }

      contextChanged = config.isServerEvaluation() && !Objects.equals(newHeader, xFeaturehubHeader);

      if (contextChanged) {
        xFeaturehubHeader = newHeader;
        // the context has changed, so the server's event stream is no longer the one we were following
        lastEventId = null;
        oldSource = eventSource;
        eventSource = null;
        connectionReady = false;
        waitingClients.add(change);
        // if a connection is already pending it will pick up the new context
        connect = !connectPending;
        connectPending = true;
      }
    }

    if (!contextChanged) {
      return poll();
    }

    log.warn("[featurehub-sdk] please only use server evaluated keys with SSE with one repository per SSE client.");

    closeInBackground(oldSource);

    if (connect) {
      submitConnect();
    }

    return change;
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
    synchronized (connectionLock) {
      closed = true;
    }

    closeEventSource();

    // always stop the retryer, even if we are not currently connected, otherwise a pending reconnect will
    // connect again after we have been closed
    retryer.close();

    // no features are coming, so don't leave anyone waiting
    notifyAllWaitingClients();

    // like the connection, closing the client can block while it is being read, so do it in the background
    closer.submit(() -> {
      try {
        client.close();
      } catch (Exception e) {
        log.trace("[featurehub-sdk] failed to close client", e);
      }
    });
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

    closer.submit(() -> closeQuietly(source));
  }

  private static ExecutorService makeCloser() {
    // threads only exist while a close is blocked, and there is at most one per connection being closed
    return Executors.newCachedThreadPool(r -> {
      final Thread t = new Thread(r, "featurehub-sse-close");
      t.setDaemon(true);
      return t;
    });
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

  /**
   * Makes the connection.
   *
   * @param header - the x-featurehub header (context) to connect with, if any
   * @param lastEventId - the last event id received for this context, if any
   */
  protected EventInput makeEventSource(@Nullable String header, @Nullable String lastEventId) {
    Invocation.Builder request = target.request();

    if (header != null) {
      request = request.header("x-featurehub", header);
    }

    if (lastEventId != null) {
      request = request.header("Last-Event-ID", lastEventId);
    }

    request = request.header("X-SDK", SdkVersion.sdkVersionHeader("Java-Jersey3"));

    log.trace("[featurehub-sdk] connecting to {}", config.getRealtimeUrl());

    return request.get(EventInput.class);
  }

  private void initEventSource() {
    EventInput source;

    while (true) {
      final String header;
      final String lastId;

      // read together so we never send one context's header with another context's last event id
      synchronized (connectionLock) {
        if (closed) {
          connectPending = false;
          return;
        }

        header = xFeaturehubHeader;
        lastId = lastEventId;
      }

      try {
        source = makeEventSource(header, lastId);
      } catch (Exception e) {
        final List<CompletableFuture<Readiness>> waiting;

        // those waiting on this attempt are taken in the same step, so anyone who starts waiting after this waits for
        // the next attempt
        synchronized (connectionLock) {
          connectPending = false;
          waiting = takeWaitingClients();
        }

        onMakeEventSourceException(e, waiting);
        return;
      }

      synchronized (connectionLock) {
        if (closed) {
          connectPending = false;
        } else if (Objects.equals(header, xFeaturehubHeader)) {
          eventSource = source;
          connectionReady = false;
          connectPending = false;
          break;
        }
      }

      // closed, or the context changed while we were connecting so this connection is for the wrong context
      closeQuietly(source);

      if (closed) {
        return;
      }

      // the context change left the connection to us (we were pending), so connect again with the new context
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
          // read timed out or the server closed the connection, the loop condition decides which. If we have been
          // replaced (e.g. by a context change) stop now, otherwise we hold up the new connection for another read.
          synchronized (connectionLock) {
            if (eventSource != source) {
              break;
            }
          }

          log.trace("server read returned no event");
          continue;
        }

        data = event.readData();
      } catch (Exception e) {
        final List<CompletableFuture<Readiness>> waiting;

        // if we are no longer the current connection we were deliberately closed (e.g. by a context change)
        synchronized (connectionLock) {
          if (eventSource != source) {
            interrupted = true;
            continue;
          }

          eventSource = null;
          waiting = takeWaitingClients();
        }

        onMakeEventSourceException(e, waiting);
        log.error("failed read", e);
        interrupted = true;
        continue;
      }

      // only the check is done under the lock, processing calls into listeners and futures which must never run
      // while we hold it
      synchronized (connectionLock) {
        if (eventSource != source) {
          // superseded by a context change, anything we received is for the old context
          break;
        }
      }

      connectionSaidBye = processResult(source, connectionSaidBye, data, event);
    }

    if (retryer.isStopped()) {
      log.trace("[featurehub] event source closed? {} interrupted? {} retryer stopped? {}", source.isClosed(), interrupted, retryer.isStopped());

      synchronized (connectionLock) {
        if (eventSource == source) {
          eventSource = null;
        }
      }

      // we are the connection thread, so we can close our own connection directly
      closeQuietly(source);

      checkForUnsatisfactoryConversation();

      notifyAllWaitingClients();
      return;
    }

    // a read failure has already given up the connection and told the retryer, so if we are still current the
    // server must have closed the connection on us
    List<CompletableFuture<Readiness>> waiting = null;

    synchronized (connectionLock) {
      if (eventSource == source) {
        eventSource = null;
        waiting = takeWaitingClients();
      }
    }

    if (waiting != null) {
      retryer.edgeResult(connectionSaidBye ? EdgeConnectionState.SERVER_SAID_BYE :
        EdgeConnectionState.SERVER_WAS_DISCONNECTED, this);

      connectionLost(waiting);
    } else {
      // superseded, closed by someone else or failed, make sure the connection is not left open
      closeQuietly(source);
    }
  }

  private void checkForUnsatisfactoryConversation() {
    // we never received a satisfactory connection
    if (repository.getReadiness() == Readiness.NotReady) {
      repository.notify(SSEResultState.FAILURE);
    }
  }

  private boolean processResult(EventInput source, boolean connectionSaidBye, String data, InboundEvent event) {
    try {
      final SSEResultState state = retryer.fromValue(event.getName());

      if (log.isTraceEnabled()) {
        log.trace("[featurehub-sdk] decode packet (state {}) {}:{}", state, event.getName(), data);
      }

      if (state != null) { // ignore unknown states
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
        if (state != SSEResultState.ACK && state != SSEResultState.CONFIG) {
          notifyWaitingClients(source);
        }
      }
    } catch (Exception e) {
      log.error("[featurehub-sdk] failed to decode packet {}:{}", event.getName(), data, e);
      // don't leave anyone waiting on an event we couldn't apply
      notifyWaitingClients(source);
      return connectionSaidBye;
    }

    // only record the id once the event has been applied, so if we failed to apply it a reconnect has the server
    // send it again
    if (event.getId() != null) {
      synchronized (connectionLock) {
        if (eventSource == source) {
          lastEventId = event.getId();
        }
      }
    }

    return connectionSaidBye;
  }

  /**
   * Tells the waiting clients we are ready, as long as the connection is still current. If a context change has
   * replaced it, those waiting are waiting for the new context's features, not the ones the old connection just sent.
   */
  private void notifyWaitingClients(@NotNull EventInput source) {
    final List<CompletableFuture<Readiness>> waiting;

    synchronized (connectionLock) {
      if (eventSource != source) {
        return;
      }

      connectionReady = true;
      waiting = takeWaitingClients();
    }

    complete(waiting);
  }

  /**
   * Tells all waiting clients where we are, used when no features are coming.
   */
  private void notifyAllWaitingClients() {
    final List<CompletableFuture<Readiness>> waiting;

    synchronized (connectionLock) {
      waiting = takeWaitingClients();
    }

    complete(waiting);
  }

  private List<CompletableFuture<Readiness>> takeWaitingClients() {
    final List<CompletableFuture<Readiness>> waiting = new ArrayList<>(waitingClients);
    waitingClients.clear();
    return waiting;
  }

  // done outside the lock, as completing a future runs its callbacks
  private void complete(List<CompletableFuture<Readiness>> waiting) {
    for (CompletableFuture<Readiness> future : waiting) {
      future.complete(repository.getReadiness());
    }
  }

  /**
   * @param waiting - those who were waiting on the failed connection (or connection attempt)
   */
  private void onMakeEventSourceException(Exception e, List<CompletableFuture<Readiness>> waiting) {
    log.info("[featurehub-sdk] failed to connect to {}", config.getRealtimeUrl());
    if (e instanceof ConnectException) {
      retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, this);
    } else if (e instanceof SocketException) {
      retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, this);
    } else if (e instanceof WebApplicationException) {
      WebApplicationException wae = (WebApplicationException) e;
      final Response response = wae.getResponse();
      // 401 and 403 are client errors too, so they must be checked first
      if (response != null && (response.getStatusInfo().getStatusCode() == 403 || response.getStatusInfo().getStatusCode() == 401)) {
        retryer.edgeResult(EdgeConnectionState.FAILURE, this);
      } else if (response != null && response.getStatusInfo().getFamily() == Response.Status.Family.CLIENT_ERROR) {
        retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, this);
      } else {
        retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, this);
      }
    } else {
      retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, this);
    }

    connectionLost(waiting);
  }

  /**
   * We have lost (or never made) the connection. Don't leave those who were waiting on it waiting for the reconnect,
   * and if the retryer has given up there won't be one, so don't leave anyone waiting at all.
   */
  private void connectionLost(List<CompletableFuture<Readiness>> waiting) {
    checkForUnsatisfactoryConversation();
    complete(waiting);

    if (retryer.isStopped()) {
      notifyAllWaitingClients();
    }
  }

  @Override
  public Future<Readiness> poll() {
    final CompletableFuture<Readiness> change = new CompletableFuture<>();

    if (connect(change)) {
      return change;
    }

    return CompletableFuture.completedFuture(repository.getReadiness());
  }

  /**
   * Asks for a connection unless we are connected, or one is already pending (waiting to run or connecting), in which
   * case asking again would make a duplicate connection.
   *
   * @param waiting - completed once features arrive, if we don't have them from the current connection yet
   * @return true if we don't have features from the current connection yet, so waiting (if any) will be completed
   * once they arrive (or the connection is lost)
   */
  private boolean connect(@Nullable CompletableFuture<Readiness> waiting) {
    final boolean submit;

    synchronized (connectionLock) {
      if (closed || retryer.isStopped() || (eventSource != null && connectionReady)) {
        return false;
      }

      if (waiting != null) {
        waitingClients.add(waiting);
      }

      if (eventSource != null) {
        // connected, but its features haven't arrived yet, they will release those waiting
        return true;
      }

      submit = !connectPending;
      connectPending = true;
    }

    if (submit) {
      submitConnect();
    }

    return true;
  }

  private void submitConnect() {
    try {
      retryer.getExecutorService().submit(this::initEventSource);
    } catch (RejectedExecutionException e) {
      // we were closed in the meantime, which shut the retryer down
      synchronized (connectionLock) {
        connectPending = false;
      }

      notifyAllWaitingClients();
    }
  }

  @Override
  public long currentInterval() {
    return 0;
  }

  @Override
  public void reconnect() {
    connect(null);
  }

  public void setNotify(Consumer<EventInput> notify) {
    this.notify = notify;
  }
}
