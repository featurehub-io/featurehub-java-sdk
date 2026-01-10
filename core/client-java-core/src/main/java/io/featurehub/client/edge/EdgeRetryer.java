package io.featurehub.client.edge;

import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.javascript.JavascriptObjectMapper;
import io.featurehub.javascript.JavascriptServiceLoader;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.SSEResultState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
   * backoffMultiplier - this is how much to multiply the backoff - the backoff is a random value between 0 and 1, which is multiplied by this
   * value.
   */
  private final int backoffMultiplier;
  private final int maximumBackoffTimeMs;
  // this will change over the lifetime of reconnect attempts, internal use only
  private int currentBackoffMultiplier;
  /**
   * if the connectionk attempt to connect fails, how long do we wait before attempting to reconnect
   */
  private final int connectionFailureBackoffTimeMs;
  private final JavascriptObjectMapper mapper = JavascriptServiceLoader.load();

  // if this is set, then we stop recognizing any further requests from the connection,
  // we can get subsequent disconnect statements. We know we cannot reconnect so we just stop.
  private boolean notFoundState = false;
  private boolean stopped = false;

  protected EdgeRetryer(int serverReadTimeoutMs, int serverDisconnectRetryMs, int serverByeReconnectMs,
                        int backoffMultiplier, int maximumBackoffTimeMs, int serverConnectTimeoutMs) {
    this.serverReadTimeoutMs = serverReadTimeoutMs;
    this.serverDisconnectRetryMs = serverDisconnectRetryMs;
    this.serverByeReconnectMs = serverByeReconnectMs;
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
        executorService.submit(() -> {
          if (serverDisconnectRetryMs > 0) {
            backoff(serverDisconnectRetryMs, true);
          }

          reConnector.reconnect();
        });
      } else if (state == EdgeConnectionState.SERVER_SAID_BYE) {
        executorService.submit(() -> {
          if (serverByeReconnectMs > 0) {
            backoff(serverByeReconnectMs, false);
          }

          reConnector.reconnect();
        });
      } else if (state == EdgeConnectionState.SERVER_READ_TIMEOUT) {
        executorService.submit(() -> {
          backoff(serverReadTimeoutMs, true);

          reConnector.reconnect();
        });
      } else if (state == EdgeConnectionState.CONNECTION_FAILURE) {
        executorService.submit(() -> {
          backoff(connectionFailureBackoffTimeMs, true);

          reConnector.reconnect();
        });
      }
    }
  }

  @Override
  public void edgeConfigInfo(String config) {
    try {
      Map<String, Object> data = mapper.readMapValue(config);

      if (data.containsKey("edge.stale")) {
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
                              @NotNull InternalFeatureRepository repository) {
    try {
      if (data != null) {
        if (state == SSEResultState.FEATURES) {
          List<FeatureState> features =
            repository.getJsonObjectMapper().readFeatureStates(data);
          repository.updateFeatures(features);
        } else if (state == SSEResultState.FEATURE) {
          repository.updateFeature(repository.getJsonObjectMapper().readValue(data,
            io.featurehub.sse.model.FeatureState.class));
        } else if (state == SSEResultState.DELETE_FEATURE) {
          repository.deleteFeature(repository.getJsonObjectMapper().readValue(data,
            io.featurehub.sse.model.FeatureState.class));
        }
      }

      if (state == SSEResultState.FAILURE) {
        repository.notify(state);
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
    try {
      Thread.sleep(calculateBackoff(baseTime, currentBackoffMultiplier));
    } catch (InterruptedException ignored) {
    }

    if (adjustBackoff) {
      currentBackoffMultiplier = newBackoff(currentBackoffMultiplier);
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
    private int backoffMultiplier;
    private int maximumBackoffTimeMs;
    private int serverConnectTimeoutMs;

    private EdgeRetryerClientType clientType = EdgeRetryerClientType.NONE;

    private EdgeRetryerBuilder() {
      // 5s by default, shouldn't be longer than that just to connect
      serverConnectTimeoutMs = propertyOrEnv("featurehub.edge.server-connect-timeout-ms", "5000");
      // 3m (180 seconds), should be higher if the server is configured for longer by default
      serverSseReadTimeoutMs = propertyOrEnv("featurehub.edge.server-sse-read-timeout-ms", "1800000");
      // 15s - should be very fast for a REST request as its a connect, read and disconnect process
      serverRestReadTimeoutMs = propertyOrEnv("featurehub.edge.server-rest-read-timeout-ms", "150000");

      serverDisconnectRetryMs = propertyOrEnv("featurehub.edge.server-disconnect-retry-ms",
        "0"); // immediately try and reconnect if disconnected
      serverByeReconnectMs = propertyOrEnv("featurehub.edge.server-by-reconnect-ms",
        "0");
      backoffMultiplier = propertyOrEnv("featurehub.edge.backoff-multiplier", "10");
      maximumBackoffTimeMs = propertyOrEnv("featurehub.edge.maximum-backoff-ms", "30000");
    }

    private int propertyOrEnv(String name, String defaultVal) {
      String val = System.getenv(name);

      if (val == null) {
        val = System.getenv(name.replace(".", "_").replace("-", "_"));
      }

      if (val == null) {
        val = System.getProperty(name, defaultVal);
      }

      return Integer.parseInt(val);
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
        , maximumBackoffTimeMs, serverConnectTimeoutMs
        );
    }
  }
}
