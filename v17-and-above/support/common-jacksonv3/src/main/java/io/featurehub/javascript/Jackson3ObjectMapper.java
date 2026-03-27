package io.featurehub.javascript;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.featurehub.sse.model.FeatureEnvironmentCollection;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.FeatureStateUpdate;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

// migration guide: https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md

public class Jackson3ObjectMapper implements JavascriptObjectMapper {
  private static ObjectMapper mapper;
  private static final Logger log = LoggerFactory.getLogger(Jackson3ObjectMapper.class);

  static {
    mapper = JsonMapper.builder()
      .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
      .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
      .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
      .changeDefaultPropertyInclusion(incl -> incl.withContentInclusion(JsonInclude.Include.NON_NULL))
      .build();

  }

  @Override
  public @Nullable String writeValueAsString(@Nullable Object data) {
    if (data == null) return null;

    try {
      return mapper.writeValueAsString(data);
    } catch (JacksonException e) {
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
