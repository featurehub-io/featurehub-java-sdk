package io.featurehub.sdk.yaml;

import io.featurehub.client.ExtendedFeatureValueInterceptor;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.utils.Conversion;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.FeatureValueType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
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

  public LocalYamlValueInterceptor(@NotNull FeatureHubConfig config,
                                   @Nullable String filename,
                                   boolean watchForChanges) {
    if (config.getInternalRepository() == null) {
      throw new RuntimeException("Cannot register interceptor with no internal repository");
    }
    this.repository = config.getInternalRepository();
    config.registerValueInterceptor(this);
    String resolved = filename != null ? filename : FeatureHubConfig.getConfig(ENV_VAR, DEFAULT_FILE);
    this.yamlFile = new File(resolved);

    loadFile();

    if (watchForChanges) {
      startWatching();
    }
  }

  public LocalYamlValueInterceptor(@NotNull FeatureHubConfig config,
                                   @Nullable String filename) {
    this(config, filename, false);
  }

  public LocalYamlValueInterceptor(@NotNull FeatureHubConfig config) {
    this(config, null, false);
  }

  void loadFile() {
    flagValues.set(YamlLoader.readFlagValues(yamlFile, log));
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
  public ValueMatch getValue(String key, InternalFeatureRepository repository, @Nullable FeatureState rawFeature) {
    Object value = flagValues.get().get(key);

    if (value == null && !flagValues.get().containsKey(key)) {
      return null;
    }

    FeatureValueType type = rawFeature != null ? rawFeature.getType() : null;
    return new ValueMatch(true, Conversion.toTypedValue(type, value, key, this.repository));
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
