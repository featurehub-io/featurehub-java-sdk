package io.featurehub.client.edge;

import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.sse.model.SSEResultState;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface EdgeRetryService {
  void edgeResult(@NotNull EdgeConnectionState state, @NotNull EdgeReconnector reconnector);

  /**
   * Edge connected received a "config" set of data, process it
   * @param config
   */
  void edgeConfigInfo(String config);

  @Nullable SSEResultState fromValue(String value);
  void convertSSEState(@NotNull SSEResultState state, String data, @NotNull InternalFeatureRepository
                       repository, UUID environmentId);

  void close();

  ExecutorService getExecutorService();

  int getServerReadTimeoutMs();

  /**
   * connection attempt in ms
   * @return
   */
  int getServerConnectTimeoutMs();

  int getServerDisconnectRetryMs();

  int getServerByeReconnectMs();

  int getMaximumBackoffTimeMs();

  int getCurrentBackoffMultiplier();

  int getBackoffMultiplier();

  boolean isNotFoundState();

  boolean isStopped();
}
