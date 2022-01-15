package todo.backend.resources;

import cd.connect.app.config.ConfigKey;
import cd.connect.app.config.DeclaredConfigResolver;
import io.featurehub.client.GoogleAnalyticsApiClient;
import todo.backend.FeatureHub;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FeatureAnalyticsFilter implements ContainerRequestFilter, ContainerResponseFilter {
  private final FeatureHub fh;
  private static final AtomicLong timeout = new AtomicLong(0L);
  @ConfigKey("feature-service.poll-interval")
  Integer pollInterval = 200; // in milliseconds

  @Inject
  public FeatureAnalyticsFilter(FeatureHub fh) {
    this.fh = fh;

    DeclaredConfigResolver.resolve(this);
  }

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    final long currentTime = System.currentTimeMillis();
    requestContext.setProperty("startTime", currentTime);

    if (currentTime - timeout.get() > pollInterval) {
      fh.poll();
      timeout.set(currentTime);
    }
  }

  @Override
  public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
    Long start = (Long)requestContext.getProperty("startTime");
    long duration = 0;
    if (start != null) {
      duration = System.currentTimeMillis() - start;
    }

    Map<String, String> other = new HashMap<>();
    other.put(GoogleAnalyticsApiClient.GA_VALUE, Long.toString(duration));
    final List<String> matchedURIs = requestContext.getUriInfo().getMatchedURIs();
    if (matchedURIs.size() > 0) {
      fh.getRepository().logAnalyticsEvent(matchedURIs.get(0), other);
    }

  }
}
