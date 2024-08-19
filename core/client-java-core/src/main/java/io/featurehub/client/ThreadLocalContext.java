package io.featurehub.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ThreadLocalContext {
  @NotNull
  private static final ThreadLocal<@Nullable ClientContext> contexts = new ThreadLocal<>();
  @Nullable
  private static FeatureHubConfig config;

  public static void setConfig(@NotNull FeatureHubConfig config) {
    ThreadLocalContext.config = config;
  }

  @NotNull public static ClientContext getContext() {
    return context();
  }

  @NotNull public static ClientContext context() {
    if (config == null) throw new RuntimeException("config not set, unable to use");

    ClientContext ctx = contexts.get();

    if (ctx == null) {
      ctx = config.newContext();
    }

    return ctx;
  }

  public static void close() {
    ClientContext ctx = contexts.get();
    if (ctx != null) {
      ctx.close();
      contexts.remove();
    }
  }
}
