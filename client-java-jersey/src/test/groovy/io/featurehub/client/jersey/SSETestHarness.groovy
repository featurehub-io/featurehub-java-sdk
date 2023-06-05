package io.featurehub.client.jersey

import io.featurehub.client.EdgeFeatureHubConfig
import io.featurehub.client.FeatureHubConfig
import org.glassfish.jersey.media.sse.EventOutput
import org.glassfish.jersey.media.sse.SseFeature
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.test.JerseyTest

import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.Application

@Path("/features")
class SSETestHarness extends JerseyTest {
  Closure<EventOutput> backhaul

  @Override
  protected Application configure() {
    return new ResourceConfig(SSETestHarness)
  }

  @GET
  @Path("{environmentId}/{apiKey}")
  @Produces(SseFeature.SERVER_SENT_EVENTS)
  public EventOutput features(
    @PathParam("environmentId") UUID envId,
    @PathParam("apiKey") String apiKey,
    @HeaderParam("x-featurehub") List<String> featureHubAttrs, // non browsers can set headers
    @HeaderParam("x-fh-extraconfig") String extraConfig,
    @QueryParam("xfeaturehub") String browserHubAttrs, // browsers can't set headers,
    @HeaderParam("Last-Event-ID") String etag) {
    return backhaul(envId, apiKey, featureHubAttrs, extraConfig, browserHubAttrs, etag)
  }

  FeatureHubConfig getConfig(List<String> apiKeys, Closure<EventOutput> backhaul) {
    this.backhaul = backhaul
    return new EdgeFeatureHubConfig(target().uri.toString(), apiKeys)
  }
}
