package io.featurehub.okhttp;

import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.edge.EdgeReconnector;
import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.edge.StreamingConnectionTracker;
import io.featurehub.client.edge.StreamingEdgeService;
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

public class SSEClient extends StreamingEdgeService<EventSource> {
  private static final Logger log = LoggerFactory.getLogger(SSEClient.class);
  // the okhttp client is made on first use. Guarded by clientLock, as two connections can be made at once (e.g. a
  // context change while a connection is still being made).
  private EventSource.Factory eventSourceFactory;
  private OkHttpClient client;
  private final Object clientLock = new Object();
  // OKHttp's connect and read timeouts if none are set
  private static final int OKHTTP_DEFAULT_TIMEOUT_MS = 10_000;

  public SSEClient(
      @Nullable InternalFeatureRepository repository,
      @NotNull FeatureHubConfig config,
      @NotNull EdgeRetryService retryer) {
    super(repository, config, retryer);
  }

  public SSEClient(@NotNull FeatureHubConfig config, @NotNull EdgeRetryService retryer) {
    this(null, config, retryer);
  }

  @Override
  protected void connect(@Nullable StreamingConnectionTracker.Connection<EventSource> connection) {
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
                  if (!process(connection, type, id, data)) {
                    // the retryer has stopped, so release the connection (any failure the cancel causes is then
                    // ignored) and close it
                    connections.connectionLost(connection, 0, workingStreamMs(), connector);
                    eventSource.cancel();
                  }
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

    } catch (IllegalArgumentException e) {
      // okhttp rejected the request, e.g. a malformed realtime url, which retrying won't fix
      log.error("[featurehub-sdk] cannot connect to {}", this.config.getRealtimeUrl(), e);
      connections.abandon(connection);
      return;
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
        client = withTimeouts(
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
                .build());

        eventSourceFactory = EventSources.createFactory(client);
      }

      factory = eventSourceFactory;
    }

    return factory.newEventSource(request, listener);
  }

  /**
   * A subclass's buildOkHttpClientBuilder may not set our timeouts, and OKHttp's 10 second default read timeout
   * drops any stream that is quiet for 10 seconds. So where a timeout is still OKHttp's default, use ours. Package
   * private for testing.
   */
  @NotNull OkHttpClient withTimeouts(@NotNull OkHttpClient built) {
    final boolean defaultRead = built.readTimeoutMillis() == OKHTTP_DEFAULT_TIMEOUT_MS;
    final boolean defaultConnect = built.connectTimeoutMillis() == OKHTTP_DEFAULT_TIMEOUT_MS;

    if (!defaultRead && !defaultConnect) {
      return built;
    }

    // a subclass that deliberately set exactly 10 seconds is indistinguishable from one that set nothing
    log.info("[featurehub-sdk] the okhttp client's {} left at okhttp's 10 second default, using the retryer's instead",
      defaultRead && defaultConnect ? "read and connect timeouts were" : defaultRead ? "read timeout was" : "connect timeout was");

    final OkHttpClient.Builder builder = built.newBuilder();

    if (defaultRead) {
      builder.readTimeout(Duration.ofMillis(retryer.getServerReadTimeoutMs()));
    }

    if (defaultConnect) {
      builder.connectTimeout(Duration.ofMillis(retryer.getServerConnectTimeoutMs()));
    }

    return builder.build();
  }

  /**
   * This is overrideable so you can make it do what you wish if you wish. A connect or read timeout left at (or set
   * to) OKHttp's 10 second default is replaced with the retryer's, so to use 10 seconds set e.g. 10001ms.
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
  protected void closeConnection(@NotNull EventSource source) {
    source.cancel();
  }

  @Override
  protected void closeLibrary() {
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
  }
}
