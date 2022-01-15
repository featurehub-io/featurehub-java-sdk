package todo.backend;

import io.featurehub.client.ClientContext;
import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureRepositoryContext;

public interface FeatureHub {
  ClientContext fhClient();
  FeatureRepositoryContext getRepository();
  void poll();
}
