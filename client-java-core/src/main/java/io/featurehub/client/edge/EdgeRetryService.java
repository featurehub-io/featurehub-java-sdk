package io.featurehub.client.edge;

import io.featurehub.sse.model.SSEResultState;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutorService;

public interface EdgeRetryService {
  void edgeResult(EdgeConnectionState state, EdgeReconnector reconnector);

  /**
   * Edge connected received a "config" set of data, process it
   * @param config
   */
  void edgeConfigInfo(String config);

  @Nullable SSEResultState fromValue(String value);

  void close();

  ExecutorService getExecutorService();

  int getServerConnectTimeoutMs();

  int getServerDisconnectRetryMs();

  int getServerByeReconnectMs();

  int getMaximumBackoffTimeMs();

  int getCurrentBackoffMultiplier();

  int getBackoffMultiplier();

  boolean isNotFoundState();
}
