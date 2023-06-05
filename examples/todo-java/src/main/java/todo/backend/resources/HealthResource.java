package todo.backend.resources;


import io.featurehub.client.Readiness;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import todo.backend.FeatureHub;

@Path("/health")
public class HealthResource {
  private final FeatureHub featureHub;

  @Inject
  public HealthResource(FeatureHub featureHub) {
    this.featureHub = featureHub;
  }

  @GET
  @Path(("/liveness"))
  public Response liveness() {
    if (featureHub.getConfig().getReadiness() == Readiness.Ready) {
      return Response.ok().build();
    }

    return Response.serverError().build();
  }
}
