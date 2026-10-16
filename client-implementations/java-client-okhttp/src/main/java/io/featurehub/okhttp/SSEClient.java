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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class SSEClient implements EdgeService, EdgeReconnector {
  private static final Logger log = LoggerFactory.getLogger(SSEClient.class);
  private final InternalFeatureRepository repository;
  private final FeatureHubConfig config;
  private volatile EventSource eventSource;
  private EventSource.Factory eventSourceFactory;
  private OkHttpClient client;
  private volatile String xFeaturehubHeader;
  @Nullable private volatile String lastEventId;
  // identifies the connection whose callbacks we act on. A connection that has been replaced (e.g. by a context
  // change) or closed may still deliver events, closes or failures, and these must be ignored.
  @Nullable private volatile Object currentConnection;
  private final Object connectionLock = new Object();
  private final EdgeRetryService retryer;
  // added to by callers' threads and drained by okhttp's threads
  private final List<CompletableFuture<Readiness>> waitingClients = new CopyOnWriteArrayList<>();
  // once closed we never connect again
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
      if (closed || currentConnection != null) {
        return;
      }

      header = xFeaturehubHeader;
      lastId = lastEventId;
      previous = eventSource;
      eventSource = null;
      currentConnection = connection;
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
                        notifyWaitingClients();
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

                  if (t instanceof java.net.ConnectException) {
                    retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, connector);
                  } else if (repository.getReadiness() == Readiness.Failed && t
                      instanceof SocketTimeoutException) {
                    // if it connects yet times out while still failed, lets back off
                    retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, connector);
                  } else {
                    retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, connector);
                  }
                }

                @Override
                public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
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
   * fix), try again later. Otherwise tell anyone waiting, as no features are coming.
   */
  private void failedToConnect(boolean wrongLibrary) {
    if (repository.getReadiness() == Readiness.NotReady) {
      repository.notify(SSEResultState.FAILURE);
    }

    if (wrongLibrary) {
      notifyWaitingClients();
    } else {
      retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, this);
    }
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

      return true;
    }
  }

  private void notifyWaitingClients() {
    for (CompletableFuture<Readiness> waiting : waitingClients) {
      waitingClients.remove(waiting);
      waiting.complete(repository.getReadiness());
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

    return eventSourceFactory.newEventSource(request, listener);
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

    if (closed) {
      change.complete(repository.getReadiness());
      return change;
    }

    final boolean contextChanged;
    EventSource oldSource = null;
    final boolean needsConnection;

    // done in one step, so a reconnect can't claim the connection between us dropping the old context's connection
    // and deciding whether to wait for the new one (and so tell the caller we are ready before any of its features
    // have arrived)
    synchronized (connectionLock) {
      contextChanged = config.isServerEvaluation() && !Objects.equals(newHeader, xFeaturehubHeader);

      if (contextChanged) {
        xFeaturehubHeader = newHeader;
        // the context has changed, so the server's event stream is no longer the one we were following
        lastEventId = null;
        currentConnection = null;
        oldSource = eventSource;
        eventSource = null;
      }

      needsConnection = currentConnection == null;

      if (needsConnection) {
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
    } else {
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
    // don't let it try connecting again
    closed = true;
    retryer.close();

    final EventSource source;

    synchronized (connectionLock) {
      currentConnection = null;
      source = eventSource;
      eventSource = null;
    }

    // shut down the pool of okhttp connections
    if (client != null) {
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }

    // cancel the event source
    if (source != null) {
      log.info("[featurehub-sdk] closing connection");
      source.cancel();
    }

    // wipe the factory
    if (eventSourceFactory != null) {
      eventSourceFactory = null;
    }

    // no features are coming, so don't leave anyone waiting
    notifyWaitingClients();
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
