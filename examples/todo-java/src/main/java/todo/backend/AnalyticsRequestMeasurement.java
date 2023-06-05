package todo.backend;

import io.featurehub.client.analytics.AnalyticsFeaturesCollection;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

public class AnalyticsRequestMeasurement extends AnalyticsFeaturesCollection {
  private final long duration;
  @NotNull
  private final String url;
  public AnalyticsRequestMeasurement(long duration, @NotNull String url) {
    super(null, null);

    this.duration = duration;
    this.url = url;
  }

  @Override
  protected @NotNull Map<String, Object> toMap() {
    final LinkedHashMap<String, Object> data = new LinkedHashMap<>(super.toMap());
    data.put("duration", duration);
    data.put("url", url);
    return data;
  }
}
