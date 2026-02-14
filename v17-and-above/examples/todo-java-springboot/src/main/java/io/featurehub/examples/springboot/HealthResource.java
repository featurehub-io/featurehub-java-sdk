package io.featurehub.examples.springboot;

import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/health")
public class HealthResource {
  private final FeatureHubConfig featureHubConfig;
  private static final Logger log = LoggerFactory.getLogger(HealthResource.class);

  @Autowired
  public HealthResource(FeatureHubConfig featureHubConfig) {
    this.featureHubConfig = featureHubConfig;
  }

  @RequestMapping("/liveness")
  public String liveness() {
    if (featureHubConfig.getReadiness() == Readiness.Ready) {
      return "yes";
    }

    log.warn("FeatureHub connection not yet available, reporting not live.");
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
