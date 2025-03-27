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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SSEClient implements EdgeService, EdgeReconnector {
  private static final Logger log = LoggerFactory.getLogger(SSEClient.class);
  private final InternalFeatureRepository repository;
  private final FeatureHubConfig config;
  private EventSource eventSource;
  private EventSource.Factory eventSourceFactory;
  private OkHttpClient client;
  private String xFeaturehubHeader;
  private final EdgeRetryService retryer;
  private final List<CompletableFuture<Readiness>> waitingClients = new ArrayList<>();

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
    if (eventSource == null) {
      initEventSource();
    }

    return CompletableFuture.completedFuture(repository.getReadiness());
  }

  @Override
  public long currentInterval() {
    return 0;
  }

  private boolean connectionSaidBye;

  private void initEventSource() {
    try {
      Request.Builder reqBuilder = new Request.Builder().url(this.config.getRealtimeUrl());

      if (xFeaturehubHeader != null) {
        reqBuilder = reqBuilder.addHeader("x-featurehub", xFeaturehubHeader);
      }

      reqBuilder.addHeader("X-SDK", SdkVersion.sdkVersionHeader("Java-OKHTTP-SSE"));

      Request request = buildRequest(reqBuilder);

      // we need to know if the connection already said "bye" so as to pass the right reconnection
      // event
      connectionSaidBye = false;
      final EdgeReconnector connector = this;

      eventSource =
          makeEventSource(
              request,
              new EventSourceListener() {
                @Override
                public void onClosed(@NotNull EventSource eventSource) {
                  log.trace("[featurehub-sdk] closed");

                  if (repository.getReadiness() == Readiness.NotReady) {
                    repository.notify(SSEResultState.FAILURE);
                  }

                  // send this once we are actually disconnected and not before
                  retryer.edgeResult(
                      connectionSaidBye
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
                  try {
                    final SSEResultState state = retryer.fromValue(type);

                    if (state == null) { // unknown state
                      return;
                    }

                    log.trace("[featurehub-sdk] decode packet {}:{}", type, data);

                    if (state == SSEResultState.CONFIG) {
                      retryer.edgeConfigInfo(data);
                    } else if (data != null) {
                      retryer.convertSSEState(state, data, repository);
                    }

                    // reset the timer
                    if (state == SSEResultState.FEATURES) {
                      retryer.edgeResult(EdgeConnectionState.SUCCESS, connector);
                    }

                    if (state == SSEResultState.BYE) {
                      connectionSaidBye = true;
                    }

                    if (state == SSEResultState.FAILURE) {
                      retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, connector);
                    }

                    // tell any waiting clients we are now ready
                    if (!waitingClients.isEmpty()
                        && (state != SSEResultState.ACK && state != SSEResultState.CONFIG)) {
                      waitingClients.forEach(wc -> wc.complete(repository.getReadiness()));
                    }
                  } catch (Exception e) {
                    log.error("[featurehub-sdk] failed to decode packet {}:{}", type, data, e);
                  }
                }

                @Override
                public void onFailure(
                    @NotNull EventSource eventSource,
                    @Nullable Throwable t,
                    @Nullable Response response) {
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
    } catch (Throwable e) {
      log.error("failed", e);
    }

    if (eventSource == null) {
      log.error("Unable to connect to {}", this.config.getRealtimeUrl());
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
              //              .readTimeout(retryer.getServerReadTimeoutMs(), TimeUnit.MILLISECONDS)
              //
              // .connectTimeout(Duration.ofMillis(retryer.getServerConnectTimeoutMs()))
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
    return new OkHttpClient.Builder();
  }

  @Override
  public @NotNull Future<Readiness> contextChange(String newHeader, String contextSha) {
    final CompletableFuture<Readiness> change = new CompletableFuture<>();

    if (config.isServerEvaluation()
        && ((newHeader != null && !newHeader.equals(xFeaturehubHeader))
            || (xFeaturehubHeader != null && !xFeaturehubHeader.equals(newHeader)))) {

      log.warn(
          "[featurehub-sdk] please only use server evaluated keys with SSE with one repository per SSE client.");

      xFeaturehubHeader = newHeader;

      if (eventSource != null) {
        eventSource.cancel();
        eventSource = null;
      }
    }

    if (eventSource == null) {
      waitingClients.add(change);

      poll();
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
    retryer.close();

    // shut down the pool of okhttp connections
    if (client != null) {
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }

    // cancel the event source
    if (eventSource != null) {
      log.info("[featurehub-sdk] closing connection");
      eventSource.cancel();
      eventSource = null;
    }

    // wipe the factory
    if (eventSourceFactory != null) {
      eventSourceFactory = null;
    }
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  @Override
  public void reconnect() {
    initEventSource();
  }
}
