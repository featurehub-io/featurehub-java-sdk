package io.featurehub.sdk.usageadapter.opentelemetry

import io.featurehub.client.EvaluatedFeature
import io.featurehub.client.usage.DefaultUsageEventWithFeature
import io.featurehub.client.usage.FeatureHubUsageValue
import io.featurehub.client.usage.UsageEvent
import io.featurehub.client.usage.UsageEventName
import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Scope
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import spock.lang.Specification

/**
 * Combined interface used by Spock mocks so a single mock object satisfies
 * both UsageEvent and UsageEventName — the two interfaces OpenTelemetryUsagePlugin requires.
 */
interface NamedUsageEvent extends UsageEvent, UsageEventName {}

class OpenTelemetryUsagePluginSpec extends Specification {

  InMemorySpanExporter exporter = InMemorySpanExporter.create()
  SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
      .addSpanProcessor(SimpleSpanProcessor.create(exporter))
      .build()

  def cleanup() {
    exporter.reset()
    tracerProvider.close()
  }

  /** Starts a recording span and makes it current; caller must end + close scope. */
  private Span startSpan() {
    return tracerProvider.get("test").spanBuilder("test-span").startSpan()
  }

  private static FeatureHubUsageValue fhValue(String key, Object rawValue, String value, FeatureValueType type) {
    def feature = new FeatureState().id(UUID.randomUUID()).environmentId(UUID.randomUUID()).key(key).value(rawValue).type(type).version(1);
    return new FeatureHubUsageValue(EvaluatedFeature.from(feature, rawValue))
  }

  // --- event type filtering ---

  def "ignores event that is not a UsageEventName"() {
    given:
      def plugin = new OpenTelemetryUsagePlugin()
      def event = Mock(UsageEvent)
    when:
      plugin.send(event)
    then:
      0 * event.toMap()
  }

  def "does nothing when toMap() is empty"() {
    given:
      def plugin = new OpenTelemetryUsagePlugin()
      def event = Mock(NamedUsageEvent) { getEventName() >> "eval"; toMap() >> [:] }
      Span span = startSpan()
      Scope scope = span.makeCurrent()
    when:
      plugin.send(event)
      span.end()
      scope.close()
    then:
      exporter.finishedSpanItems[0].attributes.isEmpty()
  }

  // --- span attribute mode (default) ---

  def "sets span attributes with default featurehub. prefix"() {
    given:
      def plugin = new OpenTelemetryUsagePlugin()
      def event = Mock(NamedUsageEvent) { getEventName() >> "eval"; toMap() >> [flag: "true", score: "42"] }
      Span span = startSpan()
      Scope scope = span.makeCurrent()
    when:
      plugin.send(event)
      span.end()
      scope.close()
    then:
      def attrs = exporter.finishedSpanItems[0].attributes
      attrs.get(AttributeKey.stringKey("featurehub.flag")) == "true"
      attrs.get(AttributeKey.stringKey("featurehub.score")) == "42"
  }

  def "uses custom prefix when specified"() {
    given:
      def plugin = new OpenTelemetryUsagePlugin("myapp.")
      def event = Mock(NamedUsageEvent) { getEventName() >> "eval"; toMap() >> [flag: "on"] }
      Span span = startSpan()
      Scope scope = span.makeCurrent()
    when:
      plugin.send(event)
      span.end()
      scope.close()
    then:
      def attrs = exporter.finishedSpanItems[0].attributes
      attrs.get(AttributeKey.stringKey("myapp.flag")) == "on"
      attrs.get(AttributeKey.stringKey("featurehub.flag")) == null
  }

  def "defaultEventParams are included with prefix"() {
    given:
      def plugin = new OpenTelemetryUsagePlugin()
      plugin.getDefaultEventParams().put("env", "prod")
      def event = Mock(NamedUsageEvent) { getEventName() >> "eval"; toMap() >> [flag: "true"] }
      Span span = startSpan()
      Scope scope = span.makeCurrent()
    when:
      plugin.send(event)
      span.end()
      scope.close()
    then:
      def attrs = exporter.finishedSpanItems[0].attributes
      attrs.get(AttributeKey.stringKey("featurehub.env")) == "prod"
      attrs.get(AttributeKey.stringKey("featurehub.flag")) == "true"
  }

  def "list values are joined as comma-separated string"() {
    given:
      def plugin = new OpenTelemetryUsagePlugin()
      def event = Mock(NamedUsageEvent) { getEventName() >> "eval"; toMap() >> [tags: ["a", "b", "c"]] }
      Span span = startSpan()
      Scope scope = span.makeCurrent()
    when:
      plugin.send(event)
      span.end()
      scope.close()
    then:
      exporter.finishedSpanItems[0].attributes.get(AttributeKey.stringKey("featurehub.tags")) == "a,b,c"
  }

  def "null values in toMap() are skipped"() {
    given:
      def plugin = new OpenTelemetryUsagePlugin()
      def event = Mock(NamedUsageEvent) { getEventName() >> "eval"; toMap() >> [missing: null, present: "yes"] }
      Span span = startSpan()
      Scope scope = span.makeCurrent()
    when:
      plugin.send(event)
      span.end()
      scope.close()
    then:
      def attrs = exporter.finishedSpanItems[0].attributes
      attrs.get(AttributeKey.stringKey("featurehub.missing")) == null
      attrs.get(AttributeKey.stringKey("featurehub.present")) == "yes"
  }

  def "works correctly with a real DefaultUsageEventWithFeature"() {
    given:
      def plugin = new OpenTelemetryUsagePlugin()
      def fhv = fhValue("dark-mode", Boolean.TRUE, "on", FeatureValueType.BOOLEAN)
      def event = new DefaultUsageEventWithFeature(fhv, null, null)
      Span span = startSpan()
      Scope scope = span.makeCurrent()
    when:
      plugin.send(event)
      span.end()
      scope.close()
    then:
      def attrs = exporter.finishedSpanItems[0].attributes
      attrs.get(AttributeKey.stringKey("featurehub.feature")) == "dark-mode"
      attrs.get(AttributeKey.stringKey("featurehub.value")) == "on"
  }

  // --- span event mode ---

  def "records a span event (not attributes) when attachAsSpanEvents is true"() {
    given:
      def plugin = new OpenTelemetryUsagePlugin("featurehub.", true)
      def event = Mock(NamedUsageEvent) { getEventName() >> "evaluated"; toMap() >> [flag: "true"] }
      Span span = startSpan()
      Scope scope = span.makeCurrent()
    when:
      plugin.send(event)
      span.end()
      scope.close()
    then:
      def spanData = exporter.finishedSpanItems[0]
      spanData.attributes.isEmpty()
      spanData.events.size() == 1
      spanData.events[0].name == "featurehub.evaluated"
      spanData.events[0].attributes.get(AttributeKey.stringKey("featurehub.flag")) == "true"
  }

  def "span event includes defaultEventParams without double-prefix"() {
    given:
      def plugin = new OpenTelemetryUsagePlugin("featurehub.", true)
      plugin.getDefaultEventParams().put("env", "prod")
      def event = Mock(NamedUsageEvent) { getEventName() >> "eval"; toMap() >> [flag: "true"] }
      Span span = startSpan()
      Scope scope = span.makeCurrent()
    when:
      plugin.send(event)
      span.end()
      scope.close()
    then:
      def eventAttrs = exporter.finishedSpanItems[0].events[0].attributes
      eventAttrs.get(AttributeKey.stringKey("featurehub.env")) == "prod"
      eventAttrs.get(AttributeKey.stringKey("featurehub.flag")) == "true"
  }
}
