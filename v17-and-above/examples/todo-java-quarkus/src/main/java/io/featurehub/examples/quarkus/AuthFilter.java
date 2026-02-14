package io.featurehub.examples.quarkus;

import com.segment.analytics.messages.IdentifyMessage;
import io.featurehub.client.ClientContext;
import io.featurehub.sdk.usageadapter.segment.SegmentAnalyticsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * This filter checks if there is an Authorization header and if so, will add it to the user context
 * (which is mutable) allowing downstream resources to correctly calculate their features.
 */
@Provider
@PreMatching
public class AuthFilter implements ContainerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

  private final SegmentAnalyticsSource segmentAnalyticsSource;

  private final jakarta.inject.Provider<ClientContext> contextProvider;

  @Inject
  public AuthFilter(SegmentAnalyticsSource segmentAnalyticsSource, jakarta.inject.Provider<ClientContext> contextProvider) {
      this.segmentAnalyticsSource = segmentAnalyticsSource;
      this.contextProvider = contextProvider;
  }

  @Override
  public void filter(ContainerRequestContext req) {
    if (req.getHeaders().containsKey("Authorization")) {
      String user = req.getHeaderString("Authorization");

      log.info("incoming request from user {}", user);

      try {
        contextProvider.get().userKey(user).build().get();

        if (segmentAnalyticsSource != null) {
          segmentAnalyticsSource
              .getAnalytics()
              .enqueue(IdentifyMessage.builder().userId(user));
        }

      } catch (Exception e) {
        log.error("Unable to set user key on user");
      }
    } else {
      log.info("request {} has no user", req.getUriInfo().getAbsolutePath().toASCIIString());
    }
  }
}
