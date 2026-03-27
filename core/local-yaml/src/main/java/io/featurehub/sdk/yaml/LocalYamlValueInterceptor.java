package io.featurehub.sdk.yaml;

import io.featurehub.client.ExtendedFeatureValueInterceptor;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.FeatureRepository;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.FeatureValueType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class LocalYamlValueInterceptor implements ExtendedFeatureValueInterceptor {
  private static final Logger log = LoggerFactory.getLogger(LocalYamlValueInterceptor.class);
  static final String ENV_VAR = "FEATUREHUB_LOCAL_YAML";
  static final String DEFAULT_FILE = "featurehub-features.yaml";

  private final File yamlFile;
  private final InternalFeatureRepository repository;
  private final AtomicReference<Map<String, Object>> flagValues = new AtomicReference<>(Collections.emptyMap());

  Thread watchThread;
  WatchService watchService;

  public LocalYamlValueInterceptor(@NotNull InternalFeatureRepository repository,
                                   @Nullable String filename,
                                   boolean watchForChanges) {
    this.repository = repository;
    String resolved = filename != null ? filename : FeatureHubConfig.getConfig(ENV_VAR, DEFAULT_FILE);
    this.yamlFile = new File(resolved);

    loadFile();

    if (watchForChanges) {
      startWatching();
    }
  }

  public LocalYamlValueInterceptor(@NotNull InternalFeatureRepository repository,
                                   @Nullable String filename) {
    this(repository, filename, false);
  }

  public LocalYamlValueInterceptor(@NotNull InternalFeatureRepository repository) {
    this(repository, null, false);
  }

  void loadFile() {
    if (!yamlFile.exists()) {
      log.debug("YAML override file {} not found, no overrides applied", yamlFile.getAbsolutePath());
      flagValues.set(Collections.emptyMap());
      return;
    }

    try (FileInputStream fis = new FileInputStream(yamlFile)) {
      Map<String, Object> data = new Yaml().load(fis);

      if (data != null && data.get("flagValues") instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) data.get("flagValues");
        flagValues.set(values);
        log.debug("Loaded {} feature override(s) from {}", values.size(), yamlFile.getName());
      } else {
        log.debug("No flagValues map found in {}", yamlFile.getName());
        flagValues.set(Collections.emptyMap());
      }
    } catch (IOException e) {
      log.error("Failed to load YAML override file {}", yamlFile.getAbsolutePath(), e);
      flagValues.set(Collections.emptyMap());
    }
  }

  private void startWatching() {
    File parentDir = yamlFile.getAbsoluteFile().getParentFile();
    if (parentDir == null || !parentDir.exists()) {
      log.warn("Cannot watch for changes: directory does not exist for {}", yamlFile.getAbsolutePath());
      return;
    }

    Path dir = parentDir.toPath();

    try {
      watchService = FileSystems.getDefault().newWatchService();
      dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);

      watchThread = new Thread(() -> {
        while (!Thread.currentThread().isInterrupted()) {
          WatchKey key;
          try {
            key = watchService.take();
          } catch (InterruptedException | ClosedWatchServiceException e) {
            break;
          }

          for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
              continue;
            }

            @SuppressWarnings("unchecked")
            Path changed = dir.resolve(((WatchEvent<Path>) event).context());

            if (changed.toAbsolutePath().equals(yamlFile.getAbsoluteFile().toPath())) {
              log.debug("Detected change in {}, reloading", yamlFile.getName());
              loadFile();
            }
          }

          key.reset();
        }
      }, "featurehub-yaml-watcher");

      watchThread.setDaemon(true);
      watchThread.start();

    } catch (IOException e) {
      log.error("Failed to start file watcher for {}", yamlFile.getAbsolutePath(), e);
    }
  }

  @Override
  public ValueMatch getValue(String key, FeatureRepository repository, @Nullable FeatureState rawFeature) {
    Object value = flagValues.get().get(key);

    if (value == null && !flagValues.get().containsKey(key)) {
      return null;
    }

    FeatureValueType type = rawFeature != null ? rawFeature.getType() : null;
    return new ValueMatch(true, toTypedValue(type, value, key));
  }

  @Nullable
  private Object toTypedValue(@Nullable FeatureValueType type, @Nullable Object value, @NotNull String key) {
    if (type == FeatureValueType.BOOLEAN) {
      if (value == null) return Boolean.FALSE;
      if (value instanceof Boolean) return value;
      return "true".equalsIgnoreCase(value.toString());
    }

    if (value == null) return null;

    if (type == FeatureValueType.NUMBER) {
      if (value instanceof Number) return new BigDecimal(value.toString());
      try {
        return new BigDecimal(value.toString());
      } catch (Exception e) {
        log.debug("Cannot convert '{}' to a number for key '{}'", value, key);
        return null;
      }
    }

    if (type == FeatureValueType.STRING) {
      if (value instanceof String || value instanceof Boolean || value instanceof Number) {
        return value.toString();
      }
      return null;
    }

    if (type == FeatureValueType.JSON) {
      return repository.getJsonObjectMapper().writeValueAsString(value);
    }

    // Unknown type — return primitives as-is (Number as BigDecimal), objects as JSON
    if (value instanceof Boolean || value instanceof String) return value;
    if (value instanceof Number) return new BigDecimal(value.toString());
    return repository.getJsonObjectMapper().writeValueAsString(value);
  }

  @Override
  public void close() {
    if (watchThread != null) {
      watchThread.interrupt();
      watchThread = null;
    }

    if (watchService != null) {
      try {
        watchService.close();
      } catch (IOException e) {
        // ignored on close
      }
      watchService = null;
    }
  }
}
