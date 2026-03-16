package io.featurehub.client;

import io.featurehub.sse.model.FeatureValueType;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

interface InternalContext extends ClientContext {
  void used(@NotNull String key, @NotNull UUID id, @Nullable Object val,
            @NotNull FeatureValueType valueType, @NotNull UUID environmentId);

  }
