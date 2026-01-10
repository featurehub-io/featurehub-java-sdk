package io.featurehub.javascript;

public class Jackson2ObjectMapperProvider implements JavascriptObjectMapperProviderService {
  @Override
  public JavascriptObjectMapper get() {
    return new Jackson2ObjectMapper();
  }
}
