package io.featurehub.okhttp;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.client.edge.EdgeReconnector;
import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.edge.StreamingConnectionTracker;
import io.featurehub.client.utils.SdkVersion;
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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class SSEClient implements EdgeService, EdgeReconnector {
  private static final Logger log = LoggerFactory.getLogger(SSEClient.class);
  private final InternalFeatureRepository repository;
  private final FeatureHubConfig config;
  // the okhttp client is made on first use. Guarded by clientLock, as two connections can be made at once (e.g. a
  // context change while a connection is still being made).
  private EventSource.Factory eventSourceFactory;
  private OkHttpClient client;
  private final Object clientLock = new Object();
  private final EdgeRetryService retryer;
  private final StreamingConnectionTracker<EventSource> connections;
  // see workingStreamMs()
  private final long workingStreamMs;

  public SSEClient(
      @Nullable InternalFeatureRepository repository,
      @NotNull FeatureHubConfig config,
      @NotNull EdgeRetryService retryer) {
    this.repository =
        repository == null ? (InternalFeatureRepository) config.getRepository() : repository;
    this.config = config;
    this.retryer = retryer;
    this.connections = new StreamingConnectionTracker<>(this.repository, config, retryer);
    this.workingStreamMs = StreamingConnectionTracker.workingStreamMs(retryer);
  }

  public SSEClient(@NotNull FeatureHubConfig config, @NotNull EdgeRetryService retryer) {
    this(null, config, retryer);
  }

  @Override
  public Future<Readiness> poll() {
    connect(connections.claim(false));

    return CompletableFuture.completedFuture(repository.getReadiness());
  }

  @Override
  public long currentInterval() {
    return 0;
  }

  /**
   * Makes the connection we have claimed, if any.
   */
  private void connect(@Nullable StreamingConnectionTracker.Connection<EventSource> connection) {
    if (connection == null) {
      return;
    }

    EventSource source = null;

    try {
      Request.Builder reqBuilder = new Request.Builder().url(this.config.getRealtimeUrl());

      if (connection.getHeader() != null) {
        reqBuilder = reqBuilder.addHeader("x-featurehub", connection.getHeader());
      }

      if (connection.getLastEventId() != null) {
        reqBuilder = reqBuilder.addHeader("Last-Event-ID", connection.getLastEventId());
      }

      reqBuilder.addHeader("X-SDK", SdkVersion.sdkVersionHeader("Java-OKHTTP-SSE"));

      Request request = buildRequest(reqBuilder);

      final EdgeReconnector connector = this;

      source =
          makeEventSource(
              request,
              new EventSourceListener() {
                @Override
                public void onClosed(@NotNull EventSource eventSource) {
                  // send this once we are actually disconnected and not before
                  if (!connections.connectionLost(connection, 0, workingStreamMs(), connector)) {
                    log.trace("[featurehub-sdk] superseded connection closed, ignoring");
                  }
                }

                @Override
                public void onEvent(
                    @NotNull EventSource eventSource,
                    @Nullable String id,
                    @Nullable String type,
                    @Nullable String data) {
                  connections.process(connection, connector, type, id, data);
                }

                @Override
                public void onFailure(
                    @NotNull EventSource eventSource,
                    @Nullable Throwable t,
                    @Nullable Response response) {
                  // a superseded connection includes the failure caused by cancelling the connection on a context
                  // change, which must not trigger a reconnect as the context change has already made a new connection
                  if (connections.connectionLost(connection, response == null ? 0 : response.code(), workingStreamMs(),
                    connector)) {
                    log.trace("[featurehub-sdk] failed to connect to {} - {}", config.baseUrl(), response, t);
                  } else {
                    log.trace("[featurehub-sdk] superseded connection failed, ignoring", t);
                  }
                }

                @Override
                public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
                  connection.opened();
                  log.trace("[featurehub-sdk] connected to {}", config.baseUrl());
                }
              });

    } catch (NoClassDefFoundError|NoSuchFieldError noClassDefFoundError) {
      log.error(
          "You appear to have the wrong version of OKHttp in your classpath or a conflicting version and FeatureHub cannot start",
          noClassDefFoundError);
      // retrying won't fix this
      connections.abandon(connection);
      return;
    } catch (Throwable e) {
      log.error("failed", e);
    }

    if (source == null) {
      // we failed to even create the connection, try again later
      if (connections.connectionLost(connection, 0, workingStreamMs(), this)) {
        log.error("Unable to connect to {}", this.config.getRealtimeUrl());
      }
    } else if (!connections.attach(connection, source)) {
      // the context changed or we were closed while connecting
      source.cancel();
    }
  }

  /**
   * @return how long a stream must stay open, even sending nothing, to count as having worked. At least a second, and
   * at least the connect timeout.
   */
  protected long workingStreamMs() {
    return workingStreamMs;
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

  protected @Nullable EventSource makeEventSource(Request request, EventSourceListener listener) {
    final EventSource.Factory factory;

    synchronized (clientLock) {
      if (connections.isClosed()) {
        // a connection that started just before we were closed, don't make a client nothing will shut down
        return null;
      }

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
    final StreamingConnectionTracker.ContextChange<EventSource> todo = connections.contextChange(newHeader, change);

    if (todo.getPrevious() != null) {
      todo.getPrevious().cancel();
    }

    connect(todo.getConnection());

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
    final EventSource source = connections.close();

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
    connections.notifyAllWaitingClients();
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  @Override
  public void reconnect() {
    // does nothing if something else (e.g. a context change) has already made a new connection
    connect(connections.claim(true));
  }
}
