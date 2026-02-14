package io.featurehub.examples.springboot;

import io.featurehub.client.EdgeFeatureHubConfig;
import io.featurehub.client.FeatureHubConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {
  @Value("${featurehub.url}")
  String edgeUrl;
  @Value("${featurehub.apiKey}")
  String apiKey;

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  public FeatureHubConfig featureHubConfig() {
    FeatureHubConfig config = new EdgeFeatureHubConfig(edgeUrl, apiKey);
    config.streaming().init();

    return config;
  }

}
