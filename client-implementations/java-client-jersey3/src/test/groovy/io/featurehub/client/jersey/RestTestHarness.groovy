package io.featurehub.client.jersey

import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.test.JerseyTest

import jakarta.inject.Singleton
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Application
import jakarta.ws.rs.core.Response

@Singleton
@Path("features")
class RestTestHarness extends JerseyTest {
  static Closure<Response> backhaul

  @Override
  protected Application configure() {
    return new ResourceConfig(RestTestHarness)
  }

  @GET
  Response features(@HeaderParam("Accept-Encoding") String acceptEncoding) {
    return backhaul(acceptEncoding)
  }

  String baseUrl(Closure<Response> backhaul) {
    this.backhaul = backhaul
    // the client adds /features/ itself
    return target().uri.toString().replaceAll('/$', '')
  }
}
