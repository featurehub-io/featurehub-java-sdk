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
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  // a connection has been asked for and is waiting to run or is connecting, so asking again would make a duplicate.
  // Guarded by connectionLock.
  private boolean connectPending;
  // once closed we never connect again
  private volatile boolean closed;
  private final WebTarget target;
  // added to by callers' threads and drained by the connection thread
  private final List<CompletableFuture<Readiness>> waitingClients = new CopyOnWriteArrayList<>();
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

    Client client = ClientBuilder.newBuilder()
      .register(JacksonFeature.class)
      .register(SseFeature.class).build();

    client.property(ClientProperties.CONNECT_TIMEOUT, retryer.getServerConnectTimeoutMs());
    client.property(ClientProperties.READ_TIMEOUT,    retryer.getServerReadTimeoutMs());

    log.trace("client set connect timeout to {} and read timeout to {}", retryer.getServerConnectTimeoutMs(), retryer.getServerReadTimeoutMs());

    target = makeEventSourceTarget(client, config.getRealtimeUrl());
  }

  @NotNull protected WebTarget makeEventSourceTarget(Client client, String sdkUrl) {
    return client.target(sdkUrl);
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, @Nullable String contextSha) {
    if (closed) {
      return CompletableFuture.completedFuture(repository.getReadiness());
    }

    final CompletableFuture<Readiness> change = new CompletableFuture<>();
    final boolean contextChanged;
    EventInput oldSource = null;
    boolean connect = false;

    // done in one step, so a connection made for the new context can't complete between us dropping the old one and
    // deciding whether to wait (and so tell the caller we are ready before any of its features have arrived)
    synchronized (connectionLock) {
      contextChanged = config.isServerEvaluation() && !Objects.equals(newHeader, xFeaturehubHeader);

      if (contextChanged) {
        xFeaturehubHeader = newHeader;
        // the context has changed, so the server's event stream is no longer the one we were following
        lastEventId = null;
        oldSource = eventSource;
        eventSource = null;
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
      retryer.getExecutorService().submit(this::initEventSource);
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
    closed = true;

    closeEventSource();

    // always stop the retryer, even if we are not currently connected, otherwise a pending reconnect will
    // connect again after we have been closed
    retryer.close();

    // no features are coming, so don't leave anyone waiting
    notifyWaitingClients();
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

    request = request.header("X-SDK", SdkVersion.sdkVersionHeader("Java-Jersey2"));

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
        synchronized (connectionLock) {
          connectPending = false;
        }

        onMakeEventSourceException(e);
        return;
      }

      synchronized (connectionLock) {
        if (closed) {
          connectPending = false;
        } else if (Objects.equals(header, xFeaturehubHeader)) {
          eventSource = source;
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
          notifyWaitingClients();
        }
      }
    } catch (Exception e) {
      log.error("[featurehub-sdk] failed to decode packet {}:{}", event.getName(), data, e);
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

  private void notifyWaitingClients() {
    for (CompletableFuture<Readiness> waiting : waitingClients) {
      waitingClients.remove(waiting);
      waiting.complete(repository.getReadiness());
    }
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
   * @param waiting - completed once features arrive, if we are not connected
   * @return true if we are not connected, so waiting (if any) will be completed once features arrive
   */
  private boolean connect(@Nullable CompletableFuture<Readiness> waiting) {
    final boolean submit;

    synchronized (connectionLock) {
      if (closed || eventSource != null) {
        return false;
      }

      if (waiting != null) {
        waitingClients.add(waiting);
      }

      submit = !connectPending;
      connectPending = true;
    }

    if (submit) {
      retryer.getExecutorService().submit(this::initEventSource);
    }

    return true;
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
