package io.featurehub.javascript;

import io.featurehub.sse.model.FeatureEnvironmentCollection;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.FeatureStateUpdate;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * We need to disconnect ourselves from which actual instance type of the Jackson ObjectMapper that
 * is being used because it changes from Jackson v2 to Jackson v3 - where it is located and how it is
 * configured. So we just provide a subset of services here and discover it using a Java Service API.
 */
public interface JavascriptObjectMapper {
  @NotNull <T> T readValue(@NotNull String data, @NotNull  Class<T> type) throws IOException;
  @NotNull Map<String, Object> readMapValue(@NotNull String data) throws IOException;

  @NotNull List<FeatureState> readFeatureStates(@NotNull String data) throws IOException;
  @NotNull List<FeatureEnvironmentCollection> readFeatureCollection(@NotNull String data) throws IOException;

  @NotNull String featureStateUpdateToString(FeatureStateUpdate data) throws IOException;
}
