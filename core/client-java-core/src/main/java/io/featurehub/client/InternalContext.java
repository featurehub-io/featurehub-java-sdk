package io.featurehub.client;

import org.jetbrains.annotations.NotNull;

interface InternalContext extends ClientContext {
  void used(@NotNull EvaluatedFeature value);
}
