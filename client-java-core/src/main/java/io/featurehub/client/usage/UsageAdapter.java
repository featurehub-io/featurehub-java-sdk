package io.featurehub.client.usage;

import io.featurehub.client.FeatureRepository;
import io.featurehub.client.RepositoryEventHandler;

import java.util.LinkedList;
import java.util.List;

public class UsageAdapter {
  private final List<UsagePlugin> plugins = new LinkedList<>();
  final FeatureRepository repository;
  final RepositoryEventHandler usageHandlerSub;

  public UsageAdapter(FeatureRepository repo) {
    this.repository = repo;
    usageHandlerSub = repo.registerUsageStream(this::process);
  }

  public void close() {
    usageHandlerSub.cancel();
  }

  public void process(UsageEvent event) {
    plugins.forEach((p) -> p.send(event));
  }

  public void registerPlugin(UsagePlugin plugin) {
    plugins.add(plugin);
  }
}
