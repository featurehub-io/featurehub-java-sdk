package io.featurehub.sdk.usageadapter.opentelemetry

import io.featurehub.client.EvaluatedFeature
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.client.usage.DefaultUsageEventWithFeature
import io.featurehub.client.usage.DefaultUsageFeaturesCollection
import io.featurehub.client.usage.FeatureHubUsageValue
import io.featurehub.client.usage.UsageEvent
import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import spock.lang.Specification

/**
 * Tests for OpenTelemetryBaggagePlugin.
 *
 * Where possible, values are verified via OpenTelemetryFeatureInterceptor (round-trip),
 * keeping tests semantic and independent of the fhub encoding format.
 * FhubBaggage encoding/decoding specifics are covered by FhubBaggageSpec.
 */
class OpenTelemetryBaggagePluginSpec extends Specification {

  OpenTelemetryBaggagePlugin plugin = new OpenTelemetryBaggagePlugin()
  OpenTelemetryFeatureInterceptor interceptor = new OpenTelemetryFeatureInterceptor(false)
  InternalFeatureRepository repo = Mock()

  private static FeatureState featureState(FeatureValueType type) {
    return new FeatureState().type(type)
  }

  private static FeatureHubUsageValue value(String key, Object rawValue, FeatureValueType type) {
    def feature = new FeatureState().id(UUID.randomUUID()).environmentId(UUID.randomUUID()).key(key).value(rawValue).type(type).version(1);
    return new FeatureHubUsageValue(EvaluatedFeature.from(feature, rawValue))

  }

  private static DefaultUsageEventWithFeature singleEvent(String key, Object rawValue, FeatureValueType type) {
    return new DefaultUsageEventWithFeature(value(key, rawValue, type), null, null)
  }

  private static DefaultUsageFeaturesCollection collectionEvent(List<FeatureHubUsageValue> values) {
    def event = new DefaultUsageFeaturesCollection()
    event.setFeatureValues(values)
    return event
  }

  /** Reads back the current fhub baggage entry (for null/empty assertions). */
  private static String currentFhub() {
    return Baggage.current().getEntryValue(OpenTelemetryFeatureInterceptor.BAGGAGE_KEY)
  }

  // --- UsageEventWithFeature: round-trip via interceptor ---

  def "boolean true written by plugin is readable as Boolean.TRUE via interceptor"() {
    given:
      Scope scope = Context.root().makeCurrent()
    when:
      plugin.send(singleEvent("dark-mode", Boolean.TRUE, FeatureValueType.BOOLEAN))
    then:
      interceptor.getValue("dark-mode", repo, featureState(FeatureValueType.BOOLEAN)).value == Boolean.TRUE
    cleanup:
      scope.close()
  }

  def "boolean false written by plugin is readable as Boolean.FALSE via interceptor"() {
    given:
      Scope scope = Context.root().makeCurrent()
    when:
      plugin.send(singleEvent("dark-mode", Boolean.FALSE, FeatureValueType.BOOLEAN))
    then:
      interceptor.getValue("dark-mode", repo, featureState(FeatureValueType.BOOLEAN)).value == Boolean.FALSE
    cleanup:
      scope.close()
  }

  def "string with spaces is URL-encoded and decoded transparently by interceptor"() {
    given:
      Scope scope = Context.root().makeCurrent()
    when:
      plugin.send(singleEvent("theme", "light blue", FeatureValueType.STRING))
    then:
      interceptor.getValue("theme", repo, featureState(FeatureValueType.STRING)).value == "light blue"
    cleanup:
      scope.close()
  }

  def "number feature is readable as BigDecimal via interceptor"() {
    given:
      Scope scope = Context.root().makeCurrent()
    when:
      plugin.send(singleEvent("page-size", new BigDecimal("42"), FeatureValueType.NUMBER))
    then:
      interceptor.getValue("page-size", repo, featureState(FeatureValueType.NUMBER)).value == new BigDecimal("42")
    cleanup:
      scope.close()
  }

  def "null raw value produces key-only entry; interceptor returns type default (false for BOOLEAN)"() {
    given:
      Scope scope = Context.root().makeCurrent()
    when:
      plugin.send(singleEvent("unset-flag", null, FeatureValueType.BOOLEAN))
    then:
      interceptor.getValue("unset-flag", repo, featureState(FeatureValueType.BOOLEAN)).value == Boolean.FALSE
    cleanup:
      scope.close()
  }

  // --- Merging ---

  def "second send for a different key merges; both readable via interceptor"() {
    given:
      Scope scope = Context.root().makeCurrent()
    when:
      plugin.send(singleEvent("beta", "b", FeatureValueType.STRING))
      plugin.send(singleEvent("alpha", "a", FeatureValueType.STRING))
    then:
      interceptor.getValue("alpha", repo, featureState(FeatureValueType.STRING)).value == "a"
      interceptor.getValue("beta", repo, featureState(FeatureValueType.STRING)).value == "b"
    cleanup:
      scope.close()
  }

  def "second send for the same key overwrites the previous value"() {
    given:
      Scope scope = Context.root().makeCurrent()
    when:
      plugin.send(singleEvent("flag", Boolean.FALSE, FeatureValueType.BOOLEAN))
      plugin.send(singleEvent("flag", Boolean.TRUE, FeatureValueType.BOOLEAN))
    then:
      interceptor.getValue("flag", repo, featureState(FeatureValueType.BOOLEAN)).value == Boolean.TRUE
    cleanup:
      scope.close()
  }

  // --- UsageFeaturesCollection ---

  def "collection event: all features readable via interceptor"() {
    given:
      Scope scope = Context.root().makeCurrent()
      def features = [
          value("zebra", Boolean.TRUE, FeatureValueType.BOOLEAN),
          value("apple", "crispy", FeatureValueType.STRING),
          value("mango", new BigDecimal("3"), FeatureValueType.NUMBER),
      ]
    when:
      plugin.send(collectionEvent(features))
    then:
      interceptor.getValue("apple", repo, featureState(FeatureValueType.STRING)).value == "crispy"
      interceptor.getValue("mango", repo, featureState(FeatureValueType.NUMBER)).value == new BigDecimal("3")
      interceptor.getValue("zebra", repo, featureState(FeatureValueType.BOOLEAN)).value == Boolean.TRUE
    cleanup:
      scope.close()
  }

  def "collection event uses raw value, not converted value (boolean raw is true/false not on/off)"() {
    given:
      Scope scope = Context.root().makeCurrent()
    when:
      plugin.send(collectionEvent([value("flag", Boolean.TRUE, FeatureValueType.BOOLEAN)]))
    then:
      // Confirm the raw Boolean round-trips correctly; "on"/"off" would fail BOOLEAN parsing
      interceptor.getValue("flag", repo, featureState(FeatureValueType.BOOLEAN)).value == Boolean.TRUE
    cleanup:
      scope.close()
  }

  def "collection event merges with features already set by a prior single event"() {
    given:
      Scope scope = Context.root().makeCurrent()
      plugin.send(singleEvent("existing", "x", FeatureValueType.STRING))
    when:
      plugin.send(collectionEvent([value("new-flag", Boolean.TRUE, FeatureValueType.BOOLEAN)]))
    then:
      interceptor.getValue("existing", repo, featureState(FeatureValueType.STRING)).value == "x"
      interceptor.getValue("new-flag", repo, featureState(FeatureValueType.BOOLEAN)).value == Boolean.TRUE
    cleanup:
      scope.close()
  }

  def "empty collection event does not update baggage"() {
    given:
      Scope scope = Context.root().makeCurrent()
    when:
      plugin.send(collectionEvent([]))
    then:
      currentFhub() == null
    cleanup:
      scope.close()
  }

  // --- Unrecognised event type ---

  def "unrecognised event type does not modify baggage"() {
    given:
      Scope scope = Context.root().makeCurrent()
      def unknownEvent = Mock(UsageEvent)
    when:
      plugin.send(unknownEvent)
    then:
      currentFhub() == null
      0 * unknownEvent.toMap()
    cleanup:
      scope.close()
  }
}
