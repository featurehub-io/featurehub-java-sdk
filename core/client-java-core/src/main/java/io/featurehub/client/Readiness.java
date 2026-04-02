package io.featurehub.client;

public enum Readiness {
  NotReady, Ready, Failed;

  public boolean isReady() {
    return this == Ready;
  }
}
