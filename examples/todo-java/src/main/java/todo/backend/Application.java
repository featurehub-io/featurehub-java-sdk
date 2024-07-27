package todo.backend;

import cd.connect.app.config.ConfigKey;
import cd.connect.app.config.DeclaredConfigResolver;
import cd.connect.lifecycle.ApplicationLifecycleManager;
import cd.connect.lifecycle.LifecycleStatus;
import jakarta.inject.Singleton;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import todo.backend.resources.FeatureAnalyticsFilter;
import todo.backend.resources.HealthResource;
import todo.backend.resources.TodoResource;

import java.net.URI;
import java.util.concurrent.TimeUnit;

public class Application {
	private static final Logger log = LoggerFactory.getLogger(Application.class);
	@ConfigKey("server.port")
	String serverPort = "8099";

  public Application() {
    DeclaredConfigResolver.resolve(this);
  }

  public void init() throws Exception {
    URI BASE_URI = URI.create(String.format("http://0.0.0.0:%s/", serverPort));

    log.info("attempting to start on port {} - will wait for features", BASE_URI.toASCIIString());

    // register our resources, try and tag them as singleton as they are instantiated faster
    ResourceConfig config = new ResourceConfig(
      TodoResource.class,
      HealthResource.class,
      FeatureAnalyticsFilter.class)
      .register(new AbstractBinder() {
        @Override
        protected void configure() {
          bind(FeatureHubSource.class).in(Singleton.class).to(FeatureHub.class);
        }
      });

    final HttpServer server = GrizzlyHttpServerFactory.createHttpServer(BASE_URI, config, false);

    server.start();

    ApplicationLifecycleManager.registerListener(trans -> {
      if (trans.next == LifecycleStatus.TERMINATING) {
        server.shutdown(10, TimeUnit.SECONDS);
      }
    });

    // tell the App we are ready
    ApplicationLifecycleManager.updateStatus(LifecycleStatus.STARTED);

    Thread.currentThread().join();
  }

  public static void main(String[] args) throws Exception {
    System.setProperty("jersey.cors.headers", "X-Requested-With,Authorization,Content-type,Accept-Version," +
      "Content-MD5,CSRF-Token,x-ijt,cache-control,x-featurehub,baggage");

    new Application().init();
	}


}
