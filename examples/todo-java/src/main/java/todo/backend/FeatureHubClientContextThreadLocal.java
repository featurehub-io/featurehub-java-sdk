package todo.backend;

import io.featurehub.client.ClientContext;

public class FeatureHubClientContextThreadLocal {
  private static final ThreadLocal<ClientContext> ctx = new ThreadLocal<>();

  public static void set(ClientContext context) {
    ctx.set(context);
  }

  public static ClientContext get() {
    return ctx.get();
  }

  public static void clear() {
    ctx.remove();
  }
}
