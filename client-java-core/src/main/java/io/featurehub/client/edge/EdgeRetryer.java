package io.featurehub.client.edge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.SSEResultState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EdgeRetryer implements EdgeRetryService {
  private static final Logger log = LoggerFactory.getLogger(EdgeRetryer.class);
  private final ExecutorService executorService;
  private final int serverConnectTimeoutMs;
  private final int serverDisconnectRetryMs;
  private final int serverByeReconnectMs;
  private final int backoffMultiplier;
  private final int maximumBackoffTimeMs;
  // this will change over the lifetime of reconnect attempts
  private int currentBackoffMultiplier;
  private final ObjectMapper mapper = new ObjectMapper();

  // if this is set, then we stop recognizing any further requests from the connection,
  // we can get subsequent disconnect statements. We know we cannot reconnect so we just stop.
  private boolean notFoundState = false;
  private boolean stopped = false;

  private final TypeReference<List<FeatureState>> FEATURE_LIST_TYPEDEF =
    new TypeReference<List<io.featurehub.sse.model.FeatureState>>() {};

  protected EdgeRetryer(int serverConnectTimeoutMs, int serverDisconnectRetryMs, int serverByeReconnectMs,
                        int backoffMultiplier, int maximumBackoffTimeMs) {
    this.serverConnectTimeoutMs = serverConnectTimeoutMs;
    this.serverDisconnectRetryMs = serverDisconnectRetryMs;
    this.serverByeReconnectMs = serverByeReconnectMs;
    this.backoffMultiplier = backoffMultiplier;
    this.maximumBackoffTimeMs = maximumBackoffTimeMs;

    currentBackoffMultiplier = backoffMultiplier;

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
      } else if (state == EdgeConnectionState.SERVER_WAS_DISCONNECTED) {
        executorService.submit(() -> {
          backoff(serverDisconnectRetryMs, true);

          reConnector.reconnect();
        });
      } else if (state == EdgeConnectionState.SERVER_SAID_BYE) {
        executorService.submit(() -> {
//          backoff(serverByeReconnectMs, false);

          reConnector.reconnect();
        });
      } else if (state == EdgeConnectionState.SERVER_CONNECT_TIMEOUT) {
        executorService.submit(() -> {
          backoff(serverConnectTimeoutMs, true);

          reConnector.reconnect();
        });
      }
    }
  }

  private static final TypeReference<Map<String, Object>> mapConfig = new TypeReference<Map<String, Object>>() {};

  @Override
  public void edgeConfigInfo(String config) {
    try {
      Map<String, Object> data = mapper.readValue(config, mapConfig);

      if (data.containsKey("edge.stale")) {
        stopped = true; // force us to stop trying for this connection
      }
    } catch (JsonProcessingException e) {
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
            repository.getJsonObjectMapper().readValue(data, FEATURE_LIST_TYPEDEF);
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
    } catch (JsonProcessingException jpe) {
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
  public int getServerConnectTimeoutMs() {
    return serverConnectTimeoutMs;
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

  public static final class EdgeRetryerBuilder {
    private int serverConnectTimeoutMs;
    private int serverDisconnectRetryMs;
    private int serverByeReconnectMs;
    private int backoffMultiplier;
    private int maximumBackoffTimeMs;

    private EdgeRetryerBuilder() {
      serverConnectTimeoutMs = propertyOrEnv("featurehub.edge.server-connect-timeout-ms", "5000");
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

    public EdgeRetryer build() {
      return new EdgeRetryer(serverConnectTimeoutMs, serverDisconnectRetryMs, serverByeReconnectMs, backoffMultiplier
        , maximumBackoffTimeMs);
    }
  }
}
