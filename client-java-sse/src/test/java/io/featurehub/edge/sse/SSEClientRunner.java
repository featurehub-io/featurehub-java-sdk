package io.featurehub.edge.sse;

import io.featurehub.client.ClientContext;
import io.featurehub.client.ClientFeatureRepository;
import io.featurehub.client.EdgeFeatureHubConfig;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.edge.EdgeRetryer;
import io.featurehub.sse.model.StrategyAttributeDeviceName;
import io.featurehub.sse.model.StrategyAttributePlatformName;

import java.util.function.Supplier;

public class SSEClientRunner {
  public static void main(String[] args) throws Exception {
    FeatureHubConfig config = new EdgeFeatureHubConfig("http://localhost:8903",
      "default/82afd7ae-e7de-4567-817b-dd684315adf7/SHxmTA83AJupii4TsIciWvhaQYBIq2*JxIKxiUoswZPmLQAIIWN");

    ClientFeatureRepository cfr = new ClientFeatureRepository();
    EdgeRetryer retryer = EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build();

    config.setEdgeService(() -> new SSEClient(cfr, config, retryer));
    config.setRepository(cfr);
    final ClientContext ctx = config.newContext(cfr, ).build().get();
    ctx.getRepository().addReadinessListener(rl -> System.out.println("readyness " + rl.toString()));

    final Supplier<Boolean> val = () -> ctx.feature("FEATURE_TITLE_TO_UPPERCASE").getBoolean();


    cfr.addReadinessListener((rl) -> System.out.println("Readyness is " + rl));

    System.out.println("Wait for readyness or hit enter if server eval key");

    System.in.read();

    ctx.userKey("jimbob")
      .platform(StrategyAttributePlatformName.MACOS)
      .device(StrategyAttributeDeviceName.DESKTOP)
      .attr("city", "istanbul").build().get();

    System.out.println("Istanbul1 is " + val.get());

    System.out.println("Press a key"); System.in.read();

    System.out.println("Istanbul2 is " + val.get());

    ctx.userKey("supine")
      .attr("city", "london").build().get();

    System.out.println("london1 is " + val.get());

    System.out.println("Press a key"); System.in.read();

    System.out.println("london2 is " + val.get());

    System.out.println("Press a key to close"); System.in.read();

    ctx.close();
    cfr.close();
  }
}
