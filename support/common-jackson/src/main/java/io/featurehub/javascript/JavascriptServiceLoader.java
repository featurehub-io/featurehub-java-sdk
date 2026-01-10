package io.featurehub.javascript;

import java.util.ServiceLoader;

/**
 * This should be called to get the default system JavascriptObjectMapper
 * library. This will be able to be overridden
 */
public class JavascriptServiceLoader {
  public static JavascriptObjectMapper load() {
    ServiceLoader<JavascriptObjectMapperProviderService> serviceLoader = ServiceLoader.load(JavascriptObjectMapperProviderService.class);

    JavascriptObjectMapperProviderService svc = serviceLoader.findFirst()
      .orElseThrow(() -> new RuntimeException("featurehub-sdk does not have available JavascriptObjectMapper implementation."));

    return svc.get();
  }
}
