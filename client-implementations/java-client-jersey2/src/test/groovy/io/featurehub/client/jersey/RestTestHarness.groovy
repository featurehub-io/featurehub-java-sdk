package io.featurehub.client.jersey

import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.test.JerseyTest

import javax.inject.Singleton
import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.Path
import javax.ws.rs.core.Application
import javax.ws.rs.core.Response

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
