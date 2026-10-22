package io.featurehub.client.edge;

import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.javascript.JavascriptObjectMapper;
import io.featurehub.javascript.JavascriptServiceLoader;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.SSEResultState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EdgeRetryer implements EdgeRetryService {
  private static final Logger log = LoggerFactory.getLogger(EdgeRetryer.class);
  private final ExecutorService executorService;
  /**
   * If we get a server-timeout failure, how long before we try and reconnect. This is the scenario where the server can be connected
   * to, but it is exceeding our READ_TIMEOUT setting. Defaults to 5s.
   */
  private final int serverReadTimeoutMs;
  /**
   * If the server disconnects from us (we get a disconnection error without a bye),  how long to wait? Normally 0.
   */
  private final int serverDisconnectRetryMs;
  /**
   * If the server disconnects from us using a BYE, how long do we wait before a reconnect. Given this is normal behaviour, we normally
   * set this to 0.
   */
  private final int serverByeReconnectMs;
  /**
   * After a BYE, how long we always wait before reconnecting (on top of serverByeReconnectMs). A stream that is accepted
   * and says bye straight away counts as working, so without this a misbehaving server could have us reconnect in a
   * tight loop. Defaults to 300ms.
   */
  private final int byeBackoffMs;
  /**
   * How long we always wait after a bye before reconnecting, unless told otherwise.
   */
  public static final int DEFAULT_BYE_BACKOFF_MS = 300;
  /**
   * backoffMultiplier - this is how much to multiply the backoff - the backoff is a random value between 0 and 1, which is multiplied by this
   * value.
   */
  private final int backoffMultiplier;
  private final int maximumBackoffTimeMs;
  // this will change over the lifetime of reconnect attempts, internal use only. Reset on connection threads and grown
  // on our own thread, so volatile.
  private volatile int currentBackoffMultiplier;
  /**
   * if the connectionk attempt to connect fails, how long do we wait before attempting to reconnect
   */
  private final int connectionFailureBackoffTimeMs;
  private final JavascriptObjectMapper mapper = JavascriptServiceLoader.load();

  // if this is set, then we stop recognizing any further requests from the connection,
  // we can get subsequent disconnect statements. We know we cannot reconnect so we just stop.
  // Set on connection threads and read on callers' threads, so volatile.
  private volatile boolean notFoundState = false;
  private volatile boolean stopped = false;
  // reconnects made before the last cancelReconnects() don't reconnect
  private final AtomicLong reconnectGeneration = new AtomicLong();
  // the generation of the reconnect running on this thread, see isReconnectCancelled()
  private final ThreadLocal<Long> reconnecting = new ThreadLocal<>();
  // reconnects that may not have run yet, so they can be cancelled. Guarded by itself.
  private final List<Future<?>> reconnects = new ArrayList<>();

  protected EdgeRetryer(int serverReadTimeoutMs, int serverDisconnectRetryMs, int serverByeReconnectMs,
                        int backoffMultiplier, int maximumBackoffTimeMs, int serverConnectTimeoutMs) {
    // the same bye backoff the builder defaults to, so subclasses written before it existed are protected too
    this(serverReadTimeoutMs, serverDisconnectRetryMs, serverByeReconnectMs, backoffMultiplier, maximumBackoffTimeMs,
      serverConnectTimeoutMs, DEFAULT_BYE_BACKOFF_MS);
  }

  protected EdgeRetryer(int serverReadTimeoutMs, int serverDisconnectRetryMs, int serverByeReconnectMs,
                        int backoffMultiplier, int maximumBackoffTimeMs, int serverConnectTimeoutMs,
                        int byeBackoffMs) {
    this.serverReadTimeoutMs = serverReadTimeoutMs;
    this.serverDisconnectRetryMs = serverDisconnectRetryMs;
    this.serverByeReconnectMs = serverByeReconnectMs;
    this.byeBackoffMs = byeBackoffMs;
    this.backoffMultiplier = backoffMultiplier;
    this.maximumBackoffTimeMs = maximumBackoffTimeMs;

    currentBackoffMultiplier = backoffMultiplier;
    this.connectionFailureBackoffTimeMs = serverConnectTimeoutMs;

    executorService = makeExecutorService();
  }

  // broken out for testability, can override with a mock pool
  protected ExecutorService makeExecutorService() {
    return Executors.newFixedThreadPool(1);
  }

  public void edgeResult(@NotNull EdgeConnectionState state, @NotNull EdgeReconnector reConnector) {
    log.trace("[featurehub-sdk] retryer triggered {}", state);
    if (!notFoundState && !stopped && !executorService.isShutdown()) {
      if (state == EdgeConnectionState.SUCCESS) {
        currentBackoffMultiplier = backoffMultiplier;
      } else if (state == EdgeConnectionState.API_KEY_NOT_FOUND) {
        log.warn("[featurehub-sdk] terminal failure attempting to connect to Edge, API KEY does not exist.");
        notFoundState = true;
        stopped = true;
      } else if (state == EdgeConnectionState.FAILURE) {
        log.warn("[featurehub-sdk] terminal failure attempting to connect to Edge, no permission.");
        notFoundState = true;
        stopped = true;
      } else if (state == EdgeConnectionState.SERVER_WAS_DISCONNECTED) {
        reconnectAfter(reConnector, () -> {
          if (serverDisconnectRetryMs > 0) {
            backoff(serverDisconnectRetryMs, true);
          }
        });
      } else if (state == EdgeConnectionState.SERVER_SAID_BYE) {
        reconnectAfter(reConnector, () -> {
          if (byeBackoffMs > 0 && !pause(byeBackoffMs)) {
            return; // cancelled
          }

          if (serverByeReconnectMs > 0) {
            backoff(serverByeReconnectMs, false);
          }
        });
      } else if (state == EdgeConnectionState.SERVER_READ_TIMEOUT) {
        reconnectAfter(reConnector, () -> backoff(serverReadTimeoutMs, true));
      } else if (state == EdgeConnectionState.CONNECTION_FAILURE) {
        reconnectAfter(reConnector, () -> backoff(connectionFailureBackoffTimeMs, true));
      }
    }
  }

  /**
   * Reconnects once we have backed off, unless the reconnect is cancelled in the meantime.
   */
  private void reconnectAfter(@NotNull EdgeReconnector reConnector, @NotNull Runnable backoff) {
    final long generation = reconnectGeneration.get();

    final Future<?> reconnect = executorService.submit(() -> {
      backoff.run();

      if (generation == reconnectGeneration.get()) {
        // the client checks again while claiming the connection, as we may be cancelled once we have got this far
        reconnecting.set(generation);

        try {
          reConnector.reconnect();
        } finally {
          reconnecting.remove();
        }
      }
    });

    if (reconnect != null) {
      synchronized (reconnects) {
        reconnects.removeIf(Future::isDone);
        reconnects.add(reconnect);
      }
    }
  }

  /**
   * Stops any reconnects that are waiting, including one that is backing off. Our executor has a single thread, so if
   * we let a cancelled reconnect sleep out its backoff, any later reconnect would be held up behind it.
   */
  @Override
  public boolean isReconnectCancelled() {
    final Long generation = reconnecting.get();
    return generation != null && generation != reconnectGeneration.get();
  }

  @Override
  public void cancelReconnects() {
    reconnectGeneration.incrementAndGet();

    synchronized (reconnects) {
      reconnects.forEach(r -> r.cancel(true));
      reconnects.clear();
    }
  }

  @Override
  public void edgeConfigInfo(String config) {
    try {
      Map<String, Object> data = mapper.readMapValue(config);

      if (data.containsKey("edge.stale")) {
        log.info("[featurehubsdk] - your SaaS account has reached the limit of the usage you have allowed yourself. Please increase usage in Billing or stop polling.");
        stopped = true; // force us to stop trying for this connection
      }
    } catch (IOException e) {
      // ignored
    }

  }

  @Override
  public @Nullable SSEResultState fromValue(String name) {
    try {
      return SSEResultState.fromValue(name);
    } catch (Exception e) {
      return null; // ok to have unrecognized values
    }
  }

  @Override
  public void convertSSEState(@NotNull SSEResultState state, String data,
                              @NotNull InternalFeatureRepository repository, UUID environmentId) {
    try {
      if (data != null) {
        if (state == SSEResultState.FEATURES) {
          List<FeatureState> features =
            repository.getJsonObjectMapper().readFeatureStates(data);
          features.forEach(f -> f.setEnvironmentId(environmentId));
          repository.updateFeatures(features, "streaming");
        } else {
          if (state == SSEResultState.FEATURE) {
            FeatureState fs = repository.getJsonObjectMapper().readValue(data, FeatureState.class);
            fs.setEnvironmentId(environmentId);
            repository.updateFeature(fs, "streaming");
          } else if (state == SSEResultState.DELETE_FEATURE) {
            FeatureState fs = repository.getJsonObjectMapper().readValue(data, FeatureState.class);
            fs.setEnvironmentId(environmentId);
            repository.deleteFeature(fs, "streaming");
          }
        }
      }

      if (state == SSEResultState.FAILURE) {
        EdgeStatus.failIfNeverReady(repository, "streaming");
      }
    } catch (IOException jpe) {
      throw new RuntimeException("JSON failed", jpe);
    }
  }

  public void close() {
    executorService.shutdownNow();
  }

  @Override
  public ExecutorService getExecutorService() {
    return executorService;
  }

  @Override
  public int getServerReadTimeoutMs() {
    return serverReadTimeoutMs;
  }

  @Override
  public int getServerConnectTimeoutMs() {
    return connectionFailureBackoffTimeMs;
  }

  @Override
  public int getServerDisconnectRetryMs() {
    return serverDisconnectRetryMs;
  }

  @Override
  public int getServerByeReconnectMs() {
    return serverByeReconnectMs;
  }

  /**
   * @return how long we always wait after a bye before reconnecting
   */
  public int getByeBackoffMs() {
    return byeBackoffMs;
  }

  @Override
  public int getMaximumBackoffTimeMs() {
    return maximumBackoffTimeMs;
  }

  @Override
  public int getCurrentBackoffMultiplier() {
    return currentBackoffMultiplier;
  }

  @Override
  public int getBackoffMultiplier() {
    return backoffMultiplier;
  }

  @Override
  public boolean isNotFoundState() {
    return notFoundState;
  }

  @Override
  public boolean isStopped() {
    return stopped;
  }

  // holds the thread for a specific period of time and then returns
  // while setting the next backoff incase we come back
  protected void backoff(int baseTime, boolean adjustBackoff) {
    if (!pause(calculateBackoff(baseTime, currentBackoffMultiplier))) {
      // the reconnect was cancelled, it never happened so it mustn't make later backoffs any longer
      return;
    }

    if (adjustBackoff) {
      currentBackoffMultiplier = newBackoff(currentBackoffMultiplier);
    }
  }

  /**
   * @return false if we were interrupted, i.e. the reconnect was cancelled (see cancelReconnects)
   */
  private boolean pause(long ms) {
    try {
      Thread.sleep(ms);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public long calculateBackoff(int baseTime, int backoff) {
    final long randomBackoff = baseTime + (long) ((1 + Math.random()) * backoff);

    final long finalBackoff = randomBackoff > maximumBackoffTimeMs ? maximumBackoffTimeMs : randomBackoff;

    log.trace("[featurehub-sdk] backing off {}", finalBackoff);

    return finalBackoff;
  }

  public int newBackoff(int currentBackoff) {
    int backoff = (int) ((1 + Math.random()) * currentBackoff);

    if (backoff < 2) {
      backoff = 3;
    }

    return backoff;
  }

  private enum EdgeRetryerClientType {
    NONE, SSE, REST
  }

  public static final class EdgeRetryerBuilder {
    private int serverSseReadTimeoutMs;
    private int serverRestReadTimeoutMs;
    private int serverDisconnectRetryMs;
    private int serverByeReconnectMs;
    private int byeBackoffMs;
    private int backoffMultiplier;
    private int maximumBackoffTimeMs;
    private int serverConnectTimeoutMs;

    private EdgeRetryerClientType clientType = EdgeRetryerClientType.NONE;

    private EdgeRetryerBuilder() {
      // 5s by default, shouldn't be longer than that just to connect
      serverConnectTimeoutMs = propertyOrEnv("featurehub.edge.server-connect-timeout-ms", "5000");
      // 3m (180 seconds), should be higher if the server is configured for longer by default
      serverSseReadTimeoutMs = propertyOrEnv("featurehub.edge.server-sse-read-timeout-ms", "180000");
      // 15s - should be very fast for a REST request as its a connect, read and disconnect process
      serverRestReadTimeoutMs = propertyOrEnv("featurehub.edge.server-rest-read-timeout-ms", "150000");

      serverDisconnectRetryMs = propertyOrEnv("featurehub.edge.server-disconnect-retry-ms",
        "0"); // immediately try and reconnect if disconnected
      serverByeReconnectMs = propertyOrEnv("featurehub.edge.server-by-reconnect-ms",
        "0");
      // always wait a little after a bye, so a server that says bye straight away can't have us reconnect in a loop
      byeBackoffMs = propertyOrEnv("featurehub.edge.bye-backoff-ms", Integer.toString(DEFAULT_BYE_BACKOFF_MS));
      backoffMultiplier = propertyOrEnv("featurehub.edge.backoff-multiplier", "10");
      maximumBackoffTimeMs = propertyOrEnv("featurehub.edge.maximum-backoff-ms", "30000");
    }

    private int propertyOrEnv(@NotNull String name, String defaultVal) {
      return Integer.parseInt(FeatureHubConfig.getConfig(name, defaultVal));
    }

    public static EdgeRetryerBuilder anEdgeRetrier() {
      return new EdgeRetryerBuilder();
    }

    public EdgeRetryerBuilder withServerConnectTimeoutMs(int serverConnectTimeoutMs) {
      this.serverConnectTimeoutMs = serverConnectTimeoutMs;
      return this;
    }

    public EdgeRetryerBuilder withServerDisconnectRetryMs(int serverDisconnectRetryMs) {
      this.serverDisconnectRetryMs = serverDisconnectRetryMs;
      return this;
    }

    public EdgeRetryerBuilder withServerByeReconnectMs(int serverByeReconnectMs) {
      this.serverByeReconnectMs = serverByeReconnectMs;
      return this;
    }

    public EdgeRetryerBuilder withByeBackoffMs(int byeBackoffMs) {
      this.byeBackoffMs = byeBackoffMs;
      return this;
    }

    public EdgeRetryerBuilder withBackoffFactorMs(int backoffFactorMs) {
      this.backoffMultiplier = backoffFactorMs;
      return this;
    }

    public EdgeRetryerBuilder withMaximumBackoffTimeMs(int maximumBackoffTimeMs) {
      this.maximumBackoffTimeMs = maximumBackoffTimeMs;
      return this;
    }

    public EdgeRetryerBuilder withSseReadTimeoutTimeMs(int ms) {
      this.serverSseReadTimeoutMs = ms;
      return this;
    }

    public EdgeRetryerBuilder withRestReadTimeoutTimeMs(int ms) {
      this.serverRestReadTimeoutMs = ms;
      return this;
    }

    public EdgeRetryerBuilder sse() {
      this.clientType = EdgeRetryerClientType.SSE;
      return this;
    }

    public EdgeRetryerBuilder rest() {
      this.clientType = EdgeRetryerClientType.REST;
      return this;
    }

    public EdgeRetryer build() {
      if (clientType == EdgeRetryerClientType.NONE) {
        throw new RuntimeException("FeatureHub Retryer does not know what read timeout to use");
      }

      return new EdgeRetryer(clientType == EdgeRetryerClientType.SSE ? serverSseReadTimeoutMs : serverRestReadTimeoutMs, serverDisconnectRetryMs, serverByeReconnectMs, backoffMultiplier
        , maximumBackoffTimeMs, serverConnectTimeoutMs, byeBackoffMs
        );
    }
  }
}
