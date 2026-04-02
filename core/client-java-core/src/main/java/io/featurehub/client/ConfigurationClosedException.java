package io.featurehub.client;

/**
 * Thrown when an operation is attempted on a {@link FeatureHubConfig} that has already been closed.
 */
public class ConfigurationClosedException extends RuntimeException {
  public ConfigurationClosedException() {
    super("FeatureHubConfig has been closed");
  }
}
