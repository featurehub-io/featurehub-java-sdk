package io.featurehub.okhttp;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.client.edge.EdgeConnectionState;
import io.featurehub.client.edge.EdgeReconnector;
import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.utils.SdkVersion;
import io.featurehub.sse.model.SSEResultState;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class SSEClient implements EdgeService, EdgeReconnector {
  private static final Logger log = LoggerFactory.getLogger(SSEClient.class);
  private final InternalFeatureRepository repository;
  private final FeatureHubConfig config;
  private volatile EventSource eventSource;
  // the okhttp client is made on first use. Guarded by clientLock, as two connections can be made at once (e.g. a
  // context change while a connection is still being made).
  private EventSource.Factory eventSourceFactory;
  private OkHttpClient client;
  private final Object clientLock = new Object();
  private volatile String xFeaturehubHeader;
  @Nullable private volatile String lastEventId;
  // identifies the connection whose callbacks we act on. A connection that has been replaced (e.g. by a context
  // change) or closed may still deliver events, closes or failures, and these must be ignored.
  @Nullable private volatile Object currentConnection;
  private final Object connectionLock = new Object();
  private final EdgeRetryService retryer;
  // has the current connection sent us features (or told us it can't) yet? Until it has, callers must wait. Guarded
  // by connectionLock.
  private boolean connectionReady;
  // added to by callers' threads and drained by okhttp's threads. Guarded by connectionLock.
  private final List<CompletableFuture<Readiness>> waitingClients = new ArrayList<>();
  // once closed we never connect again. Set under connectionLock.
  private volatile boolean closed;

  public SSEClient(
      @Nullable InternalFeatureRepository repository,
      @NotNull FeatureHubConfig config,
      @NotNull EdgeRetryService retryer) {
    this.repository =
        repository == null ? (InternalFeatureRepository) config.getRepository() : repository;
    this.config = config;
    this.retryer = retryer;
  }

  public SSEClient(@NotNull FeatureHubConfig config, @NotNull EdgeRetryService retryer) {
    this(null, config, retryer);
  }

  @Override
  public Future<Readiness> poll() {
    initEventSource();

    return CompletableFuture.completedFuture(repository.getReadiness());
  }

  @Override
  public long currentInterval() {
    return 0;
  }

  /**
   * Makes a new connection unless one is already current (connecting or connected).
   */
  private void initEventSource() {
    final Object connection = new Object();
    final String header;
    final String lastId;
    final EventSource previous;

    // read the context and claim the connection together, so a concurrent context change either happens entirely
    // before (and we connect with the new context) or entirely after (and it replaces us)
    synchronized (connectionLock) {
      if (closed || retryer.isStopped() || currentConnection != null) {
        return;
      }

      header = xFeaturehubHeader;
      lastId = lastEventId;
      previous = eventSource;
      eventSource = null;
      currentConnection = connection;
      connectionReady = false;
    }

    if (previous != null) {
      // should already be gone, but make sure we never leave a connection open. Its callbacks are ignored.
      previous.cancel();
    }

    EventSource source = null;
    boolean wrongLibrary = false;

    try {
      Request.Builder reqBuilder = new Request.Builder().url(this.config.getRealtimeUrl());

      if (header != null) {
        reqBuilder = reqBuilder.addHeader("x-featurehub", header);
      }

      if (lastId != null) {
        reqBuilder = reqBuilder.addHeader("Last-Event-ID", lastId);
      }

      reqBuilder.addHeader("X-SDK", SdkVersion.sdkVersionHeader("Java-OKHTTP-SSE"));

      Request request = buildRequest(reqBuilder);

      // we need to know if the connection already said "bye" so as to pass the right reconnection
      // event
      final AtomicBoolean connectionSaidBye = new AtomicBoolean(false);
      // did we ever get a successful response? If not, a failure means we couldn't connect at all
      final AtomicBoolean opened = new AtomicBoolean(false);
      final EdgeReconnector connector = this;

      source =
          makeEventSource(
              request,
              new EventSourceListener() {
                @Override
                public void onClosed(@NotNull EventSource eventSource) {
                  if (!release(connection)) {
                    log.trace("[featurehub-sdk] superseded connection closed, ignoring");
                    return;
                  }

                  log.trace("[featurehub-sdk] closed");

                  if (repository.getReadiness() == Readiness.NotReady) {
                    repository.notify(SSEResultState.FAILURE);
                  }

                  // send this once we are actually disconnected and not before
                  retryer.edgeResult(
                      connectionSaidBye.get()
                          ? EdgeConnectionState.SERVER_SAID_BYE
                          : EdgeConnectionState.SERVER_WAS_DISCONNECTED,
                      connector);

                  // don't leave anyone waiting while we reconnect (or if the retryer has stopped, forever)
                  notifyAllWaitingClients();
                }

                @Override
                public void onEvent(
                    @NotNull EventSource eventSource,
                    @Nullable String id,
                    @Nullable String type,
                    @Nullable String data) {
                  // a context change may have replaced this connection while events were still arriving, anything
                  // it sends is for the old context. Only the check and the id are done under the lock, processing
                  // calls into listeners and futures which must never run while we hold it.
                  synchronized (connectionLock) {
                    if (currentConnection != connection) {
                      log.trace("[featurehub-sdk] ignoring event {} from superseded connection", type);
                      return;
                    }
                  }

                  try {
                    final SSEResultState state = retryer.fromValue(type);

                    if (state != null) { // ignore unknown states
                      log.trace("[featurehub-sdk] decode packet {}:{}", type, data);

                      if (state == SSEResultState.CONFIG) {
                        retryer.edgeConfigInfo(data);
                      } else if (data != null) {
                        retryer.convertSSEState(state, data, repository, config.getEnvironmentId());
                      }

                      // reset the timer
                      if (state == SSEResultState.FEATURES) {
                        retryer.edgeResult(EdgeConnectionState.SUCCESS, connector);
                      }

                      if (state == SSEResultState.BYE) {
                        connectionSaidBye.set(true);
                      }

                      if (state == SSEResultState.FAILURE) {
                        retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, connector);
                      }

                      // tell any waiting clients we are now ready
                      if (state != SSEResultState.ACK && state != SSEResultState.CONFIG) {
                        notifyWaitingClients(connection);
                      }
                    }
                  } catch (Exception e) {
                    log.error("[featurehub-sdk] failed to decode packet {}:{}", type, data, e);
                    return;
                  }

                  // only record the id once the event has been applied, so if we failed to apply it a reconnect
                  // has the server send it again
                  if (id != null) {
                    synchronized (connectionLock) {
                      if (currentConnection == connection) {
                        lastEventId = id;
                      }
                    }
                  }
                }

                @Override
                public void onFailure(
                    @NotNull EventSource eventSource,
                    @Nullable Throwable t,
                    @Nullable Response response) {
                  if (!release(connection)) {
                    // includes the failure caused by cancelling the connection on a context change, which
                    // must not trigger a reconnect as the context change has already made a new connection
                    log.trace("[featurehub-sdk] superseded connection failed, ignoring", t);
                    return;
                  }

                  if (repository.getReadiness() == Readiness.NotReady) {
                    log.trace(
                      "[featurehub-sdk] failed to connect to {} - {}",
                      config.baseUrl(),
                      response,
                      t);
                    repository.notify(SSEResultState.FAILURE);
                  }

                  final int status = response == null ? 0 : response.code();

                  if (status == 401 || status == 403) {
                    // we aren't allowed in, retrying won't change that
                    retryer.edgeResult(EdgeConnectionState.FAILURE, connector);
                  } else if (status >= 400 && status < 500) {
                    // the api key doesn't exist, retrying won't change that
                    retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, connector);
                  } else if (t instanceof java.net.ConnectException || !opened.get()) {
                    // we never connected (e.g. the host can't be resolved, refused us or answered with a 5xx), so back
                    // off before trying again rather than reconnecting immediately as we do when a working stream drops
                    retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, connector);
                  } else if (repository.getReadiness() == Readiness.Failed && t
                      instanceof SocketTimeoutException) {
                    // if it connects yet times out while still failed, lets back off
                    retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, connector);
                  } else {
                    retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, connector);
                  }

                  // don't leave anyone waiting while we reconnect (or if the retryer has stopped, forever)
                  notifyAllWaitingClients();
                }

                @Override
                public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
                  opened.set(true);
                  log.trace("[featurehub-sdk] connected to {}", config.baseUrl());
                }
              });

    } catch (NoClassDefFoundError|NoSuchFieldError noClassDefFoundError) {
      log.error(
          "You appear to have the wrong version of OKHttp in your classpath or a conflicting version and FeatureHub cannot start",
          noClassDefFoundError);
      wrongLibrary = true;
    } catch (Throwable e) {
      log.error("failed", e);
    }

    boolean superseded = false;

    synchronized (connectionLock) {
      if (currentConnection == connection) {
        eventSource = source;

        if (source == null) {
          // we failed to even create the connection, release it so a later poll can try again
          currentConnection = null;
        }
      } else {
        superseded = true;
      }
    }

    if (source == null) {
      log.error("Unable to connect to {}", this.config.getRealtimeUrl());

      if (!superseded) {
        failedToConnect(wrongLibrary);
      }
    } else if (superseded) {
      // the context changed or we were closed while connecting
      source.cancel();
    }
  }

  /**
   * We couldn't even make the connection. Unless it is a problem with the libraries we are using (which retrying won't
   * fix), try again later. Either way don't leave anyone waiting.
   */
  private void failedToConnect(boolean wrongLibrary) {
    if (repository.getReadiness() == Readiness.NotReady) {
      repository.notify(SSEResultState.FAILURE);
    }

    if (!wrongLibrary) {
      retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, this);
    }

    notifyAllWaitingClients();
  }

  /**
   * Releases the connection if it is still the current one, so that a reconnect (or poll) can make a new one.
   *
   * @return true if it was the current connection and its close or failure should be acted on
   */
  private boolean release(@NotNull Object connection) {
    synchronized (connectionLock) {
      if (currentConnection != connection) {
        return false;
      }

      currentConnection = null;
      eventSource = null;
      connectionReady = false;

      return true;
    }
  }

  /**
   * Tells the waiting clients we are ready, as long as the connection is still current. If a context change has
   * replaced it, those waiting are waiting for the new context's features, not the ones the old connection just sent.
   */
  private void notifyWaitingClients(@NotNull Object connection) {
    final List<CompletableFuture<Readiness>> waiting;

    synchronized (connectionLock) {
      if (currentConnection != connection) {
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
   * Override this method if you wish to add extra things
   *
   * @param requestBuilder
   * @return a Request object ready for use
   */
  protected Request buildRequest(Request.Builder requestBuilder) {
    return requestBuilder.build();
  }

  protected EventSource makeEventSource(Request request, EventSourceListener listener) {
    final EventSource.Factory factory;

    synchronized (clientLock) {
      if (eventSourceFactory == null) {
        client =
          buildOkHttpClientBuilder(retryer)
                .eventListener(
                    new EventListener() {
                      @Override
                      public void connectFailed(
                          @NotNull Call call,
                          @NotNull InetSocketAddress inetSocketAddress,
                          @NotNull Proxy proxy,
                          @Nullable Protocol protocol,
                          @NotNull IOException ioe) {
                        super.connectFailed(call, inetSocketAddress, proxy, protocol, ioe);

                        log.error("connected failed");
                      }
                    })
                .build();

        eventSourceFactory = EventSources.createFactory(client);
      }

      factory = eventSourceFactory;
    }

    return factory.newEventSource(request, listener);
  }

  /**
   * This is overrideable so you can make it do what you wish if you wish.
   *
   * @param edgeRetryService
   * @return - new builder
   */
  @NotNull
  protected OkHttpClient.Builder buildOkHttpClientBuilder(@NotNull EdgeRetryService edgeRetryService) {
    // the read timeout must be long, otherwise OKHttp's 10 second default drops the connection whenever
    // there is no traffic on the stream. The server is expected to disconnect us.
    return new OkHttpClient.Builder()
      .connectTimeout(Duration.ofMillis(edgeRetryService.getServerConnectTimeoutMs()))
      .readTimeout(Duration.ofMillis(edgeRetryService.getServerReadTimeoutMs()));
  }

  @Override
  public @NotNull Future<Readiness> contextChange(String newHeader, String contextSha) {
    final CompletableFuture<Readiness> change = new CompletableFuture<>();
    final boolean contextChanged;
    EventSource oldSource = null;
    final boolean needsConnection;
    final boolean waiting;

    // done in one step, so a reconnect can't claim the connection between us dropping the old context's connection
    // and deciding whether to wait for the new one (and so tell the caller we are ready before any of its features
    // have arrived)
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
        currentConnection = null;
        connectionReady = false;
        oldSource = eventSource;
        eventSource = null;
      }

      needsConnection = currentConnection == null;
      // a connection that hasn't sent us its features yet will tell us when it has
      waiting = needsConnection || !connectionReady;

      if (waiting) {
        waitingClients.add(change);
      }
    }

    if (contextChanged) {
      log.warn(
          "[featurehub-sdk] please only use server evaluated keys with SSE with one repository per SSE client.");
    }

    if (oldSource != null) {
      oldSource.cancel();
    }

    if (needsConnection) {
      // if a reconnect beat us to it, this does nothing and we wait for its features
      initEventSource();
    } else if (!waiting) {
      change.complete(repository.getReadiness());
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
    final EventSource source;

    synchronized (connectionLock) {
      // don't let it try connecting again
      closed = true;
      currentConnection = null;
      source = eventSource;
      eventSource = null;
    }

    retryer.close();

    final OkHttpClient okClient;

    synchronized (clientLock) {
      okClient = client;
      client = null;
      eventSourceFactory = null;
    }

    // shut down the pool of okhttp connections
    if (okClient != null) {
      okClient.dispatcher().executorService().shutdownNow();
      okClient.connectionPool().evictAll();
    }

    // cancel the event source
    if (source != null) {
      log.info("[featurehub-sdk] closing connection");
      source.cancel();
    }

    // no features are coming, so don't leave anyone waiting
    notifyAllWaitingClients();
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  @Override
  public void reconnect() {
    // does nothing if something else (e.g. a context change) has already made a new connection
    initEventSource();
  }
}
