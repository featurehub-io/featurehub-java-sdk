package io.featurehub.javascript;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.featurehub.sse.model.FeatureEnvironmentCollection;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.FeatureStateUpdate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class Jackson2ObjectMapper implements JavascriptObjectMapper {
  private static ObjectMapper mapper;
  private static final Logger log = LoggerFactory.getLogger(Jackson2ObjectMapper.class);

  static {
    mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
  }

  @Override
  public @Nullable String writeValueAsString(@Nullable Object data) {
    if (data == null) return null;

    try {
      return mapper.writeValueAsString(data);
    } catch (JsonProcessingException e) {
      log.error("Unable to write object as string", e);
      return null;
    }
  }

  @Override
  public <T> T readValue(String data, Class<T> type) throws IOException {
    return data == null ? null : mapper.readValue(data, type);
  }

  private static final TypeReference<List<FeatureEnvironmentCollection>> FEATURE_COLLECTION_TYPEREF = new TypeReference<List<FeatureEnvironmentCollection>>(){};
  private static final TypeReference<Map<String, Object>> mapConfig = new TypeReference<Map<String, Object>>() {};
  private static final TypeReference<List<FeatureState>> FEATURE_LIST_TYPEDEF =
    new TypeReference<>() {};

  @Override
  public Map<String, Object> readMapValue(String data) throws IOException {
    return mapper.readValue(data, mapConfig);
  }

  @Override
  public @NotNull List<FeatureState> readFeatureStates(@NotNull String data) throws IOException {
    return mapper.readValue(data, FEATURE_LIST_TYPEDEF);
  }

  @Override
  public @NotNull List<FeatureEnvironmentCollection> readFeatureCollection(@NotNull String data) throws IOException {
    return mapper.readValue(data, FEATURE_COLLECTION_TYPEREF);
  }

  @Override
  public @NotNull String featureStateUpdateToString(FeatureStateUpdate data) throws IOException {
    return mapper.writeValueAsString(data);
  }
}
