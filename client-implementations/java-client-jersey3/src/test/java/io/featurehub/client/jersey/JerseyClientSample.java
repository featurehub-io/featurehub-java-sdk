package io.featurehub.client.jersey;

import io.featurehub.client.ClientContext;
import io.featurehub.client.ClientFeatureRepository;
import io.featurehub.client.EdgeFeatureHubConfig;
import io.featurehub.client.Feature;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.FeatureRepository;
import io.featurehub.client.edge.EdgeRetryer;
import io.featurehub.sse.model.FeatureStateUpdate;
import io.featurehub.sse.model.StrategyAttributeDeviceName;
import io.featurehub.sse.model.StrategyAttributePlatformName;

import java.util.function.Supplier;

enum Features implements Feature {
  FEATURE_SAMPLE, feature_json, feature_number, NEW_BOAT, NEW_BUTTON, SHOULDNT_exist;
}

public class JerseyClientSample {
  public static void main(String[] args) throws Exception {
    // use www.google.com:81 to test server connection timeout
    final FeatureHubConfig config = new EdgeFeatureHubConfig("http://localhost:8064/pistachio",
      "default/2f4de83c-e13e-459e-b272-63e4f8b34bad/ReQpGic7lOaZuQDkxe3WD40EbtVDN1*z5iXRNRROCW4Gy2peXsr");

    config.addReadinessListener((rl) -> System.out.println("Readyness is " + rl));

    config.setEdgeService(() -> new JerseySSEClient(config.getInternalRepository(), config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().build()));

    config.init();
    final ClientContext ctx = config.newContext();

    final Supplier<Boolean> val = () -> ctx.feature("FEATURE_TITLE_TO_UPPERCASE").isEnabled();

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
  }

  private final static String changeToggleEnv = "default/fc5b929b-8296-4920-91ef-6e5b58b499b9" +
    "/VNftuX5LV6PoazPZsEEIBujM4OBqA1Iv9f9cBGho2LJylvxXMXKGxwD14xt2d7Ma3GHTsdsSO8DTvAYF";

//  @Test
  public void changeToggleTest() {
//    ClientFeatureRepository cfr = new ClientFeatureRepository(5);
//
//    final JerseyClient client =
//      new JerseyClient(new EdgeFeatureHubConfig("http://localhost:8553", changeToggleEnv), false,
//        cfr, null);
//
//    client.setFeatureState("NEW_BOAT", new FeatureStateUpdate().lock(false).value(Boolean.TRUE));
  }
}
