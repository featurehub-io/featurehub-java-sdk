package io.featurehub.sdk.usageadapter.opentelemetry;

import io.featurehub.client.usage.UsageEvent;
import io.featurehub.client.usage.UsageEventName;
import io.featurehub.client.usage.UsagePlugin;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class OpenTelemetryUsagePlugin extends UsagePlugin {
  private static final Logger log = LoggerFactory.getLogger(OpenTelemetryUsagePlugin.class);

  private final String prefix;

  public OpenTelemetryUsagePlugin(String prefix) {
    this.prefix = prefix;
  }

  public OpenTelemetryUsagePlugin() {
    this("featurehub.");
  }

  @Override
  public void send(UsageEvent event) {
    final Span current = Span.current();
    if (current != null && event instanceof UsageEventName) {
      final String name = ((UsageEventName) event).getEventName();

      final Map<String, ?> usageAttributes = event.toMap();

      log.trace("opentelemetry - logging {} with attributes {}", name, usageAttributes);

      if (!usageAttributes.isEmpty()) {
        final AttributesBuilder builder = Attributes.builder();

        defaultEventParams.forEach((k, v) -> putMe(k, v, builder));
        usageAttributes.forEach((k, v) -> putMe(k, v, builder));

        current.addEvent(prefix(name), builder.build(), Instant.now());
      }
    }
  }

  private String prefix(String name) {
    return prefix + name;
  }

  private void putMe(String k, Object v, AttributesBuilder builder) {
    if (v instanceof List) {
      List<?> list = (List<?>) v;
      final String result = list.stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.joining(","));
      builder.put(prefix(k), result);
    } else if (v != null) {
      builder.put(prefix(k), v.toString());
    }
  }
}
