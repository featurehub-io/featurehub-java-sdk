package io.featurehub.client.jersey

import io.featurehub.client.EdgeFeatureHubConfig
import io.featurehub.client.FeatureHubConfig
import org.glassfish.jersey.media.sse.EventOutput
import org.glassfish.jersey.media.sse.SseFeature
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.test.JerseyTest
import org.glassfish.jersey.test.TestProperties

import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.Application

//@Singleton
@Path("features/{environmentId}/{apiKey}")
class SSETestHarness extends JerseyTest {
  static Closure<EventOutput> backhaul

  @Override
  protected Application configure() {
    enable(TestProperties.LOG_TRAFFIC)
    enable(TestProperties.DUMP_ENTITY)
//    forceSet(TestProperties.CONTAINER_PORT, "0")
    return new ResourceConfig(SSETestHarness)
  }

  @GET
  @Produces(SseFeature.SERVER_SENT_EVENTS)
  public EventOutput features(
    @PathParam("environmentId") String envId,
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
