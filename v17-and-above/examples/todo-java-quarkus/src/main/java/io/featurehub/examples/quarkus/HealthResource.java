package io.featurehub.examples.quarkus;

import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.Readiness;

import io.quarkus.runtime.Startup;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * This follows our Java recommendation patterns to return a 503 until you have a connected repository. If the
 * connection to the feature server permanently goes down, this would stop routing traffic to this server.
 */
@Path("/health/liveness")
public class HealthResource {
  private final FeatureHubConfig config;

  @Inject
  public HealthResource(FeatureHubConfig config) {
    this.config = config;
  }

  @GET
  public Response liveness() {
    if (config.getReadiness() == Readiness.Ready) {
      return Response.ok().build();
    }

    return Response.status(503).build();
  }
}
