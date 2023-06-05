package io.featurehub.client;

import io.featurehub.sse.model.FeatureValueType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

interface InternalContext extends ClientContext {
  void used(@NotNull String key, @NotNull UUID id, @Nullable Object val,
            @NotNull FeatureValueType valueType);

  }
