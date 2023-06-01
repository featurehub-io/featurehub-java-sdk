package todo.backend;

import io.featurehub.client.ClientContext;
import io.featurehub.client.FeatureHubConfig;

import java.util.concurrent.Future;

public interface FeatureHub {
  ClientContext fhClient();
  FeatureHubConfig getConfig();
  Future<?> poll();
}
