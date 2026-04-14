package io.featurehub.sdk.usageadapter.opentelemetry

import io.featurehub.client.ExtendedFeatureValueInterceptor.ValueMatch
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.context.Scope
import spock.lang.Specification

class OpenTelemetryFeatureInterceptorSpec extends Specification {

  InternalFeatureRepository repo = Mock()

  private static FeatureState feature(FeatureValueType type, boolean locked = false) {
    return new FeatureState().type(type).l(locked)
  }

  // --- no baggage / key not found ---

  def "returns no-match when fhub baggage entry is absent"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
    when:
      ValueMatch result = interceptor.getValue("flag", repo, null)
    then:
      !result.matched
      result.value == null
  }

  def "returns no-match when key is not present in fhub list"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "aaa=1,zzz=2").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("mmm", repo, null)
    then:
      !result.matched
      result.value == null
    cleanup:
      scope.close()
  }

  def "returns no-match when key is alphabetically absent from the fhub list"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "beta=1,gamma=2").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("alpha", repo, null)
    then:
      !result.matched
    cleanup:
      scope.close()
  }

  // --- BOOLEAN ---

  def "matches BOOLEAN true from baggage"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "dark-mode=true").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("dark-mode", repo, feature(FeatureValueType.BOOLEAN))
    then:
      result.matched
      result.value == Boolean.TRUE
    cleanup:
      scope.close()
  }

  def "matches BOOLEAN false from baggage"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "dark-mode=false").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("dark-mode", repo, feature(FeatureValueType.BOOLEAN))
    then:
      result.matched
      result.value == Boolean.FALSE
    cleanup:
      scope.close()
  }

  // --- NUMBER ---

  def "matches NUMBER from baggage and returns BigDecimal"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "page-size=42").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("page-size", repo, feature(FeatureValueType.NUMBER))
    then:
      result.matched
      result.value == new BigDecimal("42")
    cleanup:
      scope.close()
  }

  // --- STRING ---

  def "matches STRING from baggage"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "theme=dark").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("theme", repo, feature(FeatureValueType.STRING))
    then:
      result.matched
      result.value == "dark"
    cleanup:
      scope.close()
  }

  // --- URL encoding ---

  def "URL-decodes the value before converting"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "theme=light%20blue").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("theme", repo, feature(FeatureValueType.STRING))
    then:
      result.matched
      result.value == "light blue"
    cleanup:
      scope.close()
  }

  // --- rawFeature null (unknown / unregistered type) ---

  def "returns matched string when rawFeature is null"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "flag=hello").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("flag", repo, null)
    then:
      result.matched
      result.value == "hello"
    cleanup:
      scope.close()
  }

  // --- entry with no '=' sign ---

  def "key-only entry (no equals sign) produces BOOLEAN false via null value"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "flag").build().makeCurrent()
    when:
      // Conversion.toTypedValue with BOOLEAN + null value returns FALSE
      ValueMatch result = interceptor.getValue("flag", repo, feature(FeatureValueType.BOOLEAN))
    then:
      result.matched
      result.value == Boolean.FALSE
    cleanup:
      scope.close()
  }

  // --- locked feature handling ---

  def "does not override a locked feature when allowLockedOverride is false"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "flag=true").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("flag", repo, feature(FeatureValueType.BOOLEAN, true))
    then:
      !result.matched
    cleanup:
      scope.close()
  }

  def "overrides a locked feature when allowLockedOverride is true (explicit param)"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(true)
      Scope scope = Baggage.builder().put("fhub", "flag=true").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("flag", repo, feature(FeatureValueType.BOOLEAN, true))
    then:
      result.matched
      result.value == Boolean.TRUE
    cleanup:
      scope.close()
  }

  def "no-arg constructor defaults to disallowing locked overrides"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor()
      Scope scope = Baggage.builder().put("fhub", "flag=true").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("flag", repo, feature(FeatureValueType.BOOLEAN, true))
    then:
      !result.matched
    cleanup:
      scope.close()
  }

  def "unlocked feature is always overridable regardless of allowLockedOverride setting"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "flag=true").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("flag", repo, feature(FeatureValueType.BOOLEAN, false))
    then:
      result.matched
      result.value == Boolean.TRUE
    cleanup:
      scope.close()
  }

  // --- FeatureHubConfig constructor ---

  def "FeatureHubConfig constructor self-registers the interceptor with the config"() {
    given:
      FeatureHubConfig config = Mock()
    when:
      new OpenTelemetryFeatureInterceptor(config)
    then:
      1 * config.registerValueInterceptor(_ as OpenTelemetryFeatureInterceptor)
  }

  def "FeatureHubConfig constructor with explicit allowLockedOverride registers and applies the setting"() {
    given:
      FeatureHubConfig config = Mock()
      Scope scope = Baggage.builder().put("fhub", "flag=true").build().makeCurrent()
    when:
      def interceptor = new OpenTelemetryFeatureInterceptor(config, true)
      ValueMatch result = interceptor.getValue("flag", repo, feature(FeatureValueType.BOOLEAN, true))
    then:
      1 * config.registerValueInterceptor(_ as OpenTelemetryFeatureInterceptor)
      result.matched
      result.value == Boolean.TRUE
    cleanup:
      scope.close()
  }

  // --- multi-entry list ---

  def "selects the correct entry from a multi-entry alphabetically-sorted fhub list"() {
    given:
      def interceptor = new OpenTelemetryFeatureInterceptor(false)
      Scope scope = Baggage.builder().put("fhub", "aaa=1,bbb=2,ccc=3").build().makeCurrent()
    when:
      ValueMatch result = interceptor.getValue("bbb", repo, feature(FeatureValueType.NUMBER))
    then:
      result.matched
      result.value == new BigDecimal("2")
    cleanup:
      scope.close()
  }
}
