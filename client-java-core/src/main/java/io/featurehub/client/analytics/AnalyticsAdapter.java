package io.featurehub.client.analytics;

import io.featurehub.client.FeatureRepository;
import io.featurehub.client.RepositoryEventHandler;

import java.util.LinkedList;
import java.util.List;

public class AnalyticsAdapter {
  private List<AnalyticsPlugin> plugins = new LinkedList<>();
  final FeatureRepository repository;
  final RepositoryEventHandler analyticsHandlerSub;

  public AnalyticsAdapter(FeatureRepository repo) {
    this.repository = repo;
    analyticsHandlerSub = repo.registerAnalyticsStream(this::process);
  }

  public void close() {
    analyticsHandlerSub.cancel();
  }

  public void process(AnalyticsEvent event) {
    plugins.forEach((p) -> p.send(event));
  }
}
