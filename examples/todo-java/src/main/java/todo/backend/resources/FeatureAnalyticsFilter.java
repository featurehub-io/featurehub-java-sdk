package todo.backend.resources;

import cd.connect.app.config.DeclaredConfigResolver;
import io.featurehub.client.ClientContext;
import io.featurehub.client.ThreadLocalContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import todo.backend.FeatureHub;
import todo.backend.FeatureHubClientContextThreadLocal;
import todo.backend.UsageRequestMeasurement;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class FeatureAnalyticsFilter implements ContainerRequestFilter, ContainerResponseFilter {

  private final FeatureHub config;
  private static final Logger log = LoggerFactory.getLogger(FeatureAnalyticsFilter.class);

  @Inject
  public FeatureAnalyticsFilter(FeatureHub config) {
    this.config = config;
    DeclaredConfigResolver.resolve(this);
  }

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    final long currentTime = System.currentTimeMillis();
    requestContext.setProperty("startTime", currentTime);
    final List<String> user = requestContext.getUriInfo().getPathParameters().get("user");
    if (user != null && !user.isEmpty()) {
      try {
        requestContext.setProperty("context", config.getConfig().newContext().build().get());
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
    Long start = (Long) requestContext.getProperty("startTime");
    ClientContext context = (ClientContext) requestContext.getProperty("context");

    FeatureHubClientContextThreadLocal.clear();

    if (start != null && context != null) {
      long duration = System.currentTimeMillis() - start;

      final List<String> matchedURIs = requestContext.getUriInfo().getMatchedURIs();

      if (!matchedURIs.isEmpty()) {
        context.recordUsageEvent(new UsageRequestMeasurement(duration, matchedURIs.get(0)));
      }
    } else {
      log.error("There was not start time {} and context {}", start, context);
    }
  }
}
