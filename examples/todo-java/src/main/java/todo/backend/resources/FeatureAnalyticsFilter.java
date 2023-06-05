package todo.backend.resources;

import cd.connect.app.config.DeclaredConfigResolver;
import io.featurehub.client.ThreadLocalContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import todo.backend.AnalyticsRequestMeasurement;

import java.io.IOException;
import java.util.List;

public class FeatureAnalyticsFilter implements ContainerRequestFilter, ContainerResponseFilter {

  @Inject
  public FeatureAnalyticsFilter() {
    DeclaredConfigResolver.resolve(this);
  }

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    final long currentTime = System.currentTimeMillis();
    requestContext.setProperty("startTime", currentTime);
  }

  @Override
  public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
    Long start = (Long)requestContext.getProperty("startTime");
    long duration = 0;
    if (start != null) {
      duration = System.currentTimeMillis() - start;
    }

    final List<String> matchedURIs = requestContext.getUriInfo().getMatchedURIs();
    if (matchedURIs.size() > 0) {
      ThreadLocalContext.context().recordAnalyticsEvent(new AnalyticsRequestMeasurement(duration, matchedURIs.get(0)));
    }
  }
}
