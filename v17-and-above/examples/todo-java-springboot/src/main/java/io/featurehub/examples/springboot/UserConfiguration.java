package io.featurehub.examples.springboot;

import io.featurehub.client.ClientContext;
import io.featurehub.client.FeatureHubConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import jakarta.servlet.http.HttpServletRequest;

@Configuration
public class UserConfiguration {
  @Bean
  @Scope("request")
  ClientContext featureHubClient(FeatureHubConfig fhConfig, HttpServletRequest request) {
    ClientContext fhClient = fhConfig.newContext();

    if (request.getHeader("Authorization") != null) {
      // you would always authenticate some other way, this is just an example
      fhClient.userKey(request.getHeader("Authorization"));
    }

    return fhClient;
  }
}
