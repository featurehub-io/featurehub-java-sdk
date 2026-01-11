package io.featurehub.javascript;

public class Jackson3ObjectMapperProvider implements JavascriptObjectMapperProviderService {
  @Override
  public JavascriptObjectMapper get() {
    return new Jackson3ObjectMapper();
  }
}
