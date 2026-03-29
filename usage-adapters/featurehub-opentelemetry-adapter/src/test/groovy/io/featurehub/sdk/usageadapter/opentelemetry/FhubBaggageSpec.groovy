package io.featurehub.sdk.usageadapter.opentelemetry

import spock.lang.Specification

import java.util.TreeMap

class FhubBaggageSpec extends Specification {

  // --- parse ---

  def "parse null returns empty map"() {
    expect:
      FhubBaggage.parse(null).isEmpty()
  }

  def "parse empty string returns empty map"() {
    expect:
      FhubBaggage.parse("").isEmpty()
  }

  def "parse key-only entry (no equals sign) stores null value"() {
    when:
      def result = FhubBaggage.parse("nullflag")
    then:
      result.size() == 1
      result.containsKey("nullflag")
      result["nullflag"] == null
  }

  def "parse single key=value entry"() {
    expect:
      FhubBaggage.parse("flag=true") == ["flag": "true"]
  }

  def "parse multiple comma-separated entries"() {
    expect:
      FhubBaggage.parse("alpha=1,beta=2,gamma=3") == ["alpha": "1", "beta": "2", "gamma": "3"]
  }

  def "parse preserves encoded values without decoding"() {
    expect:
      FhubBaggage.parse("theme=light%20blue") == ["theme": "light%20blue"]
  }

  def "parse mixed null and non-null values"() {
    when:
      def result = FhubBaggage.parse("aaa=x,bbb,ccc=y")
    then:
      result["aaa"] == "x"
      result["bbb"] == null
      result["ccc"] == "y"
  }

  // --- build ---

  def "build empty map returns empty string"() {
    expect:
      FhubBaggage.build(new TreeMap<String, String>()) == ""
  }

  def "build key-only entry (null value) has no equals sign"() {
    given:
      def map = new TreeMap<String, String>([nullflag: null])
    expect:
      FhubBaggage.build(map) == "nullflag"
  }

  def "build multiple entries produces alphabetically sorted comma-separated string"() {
    given:
      def map = new TreeMap<String, String>([gamma: "3", alpha: "1", beta: "2"])
    expect:
      FhubBaggage.build(map) == "alpha=1,beta=2,gamma=3"
  }

  def "build mixed null and non-null values"() {
    given:
      def map = new TreeMap<String, String>([aaa: "x", bbb: null])
    expect:
      FhubBaggage.build(map) == "aaa=x,bbb"
  }

  // --- encode ---

  def "encode null returns null"() {
    expect:
      FhubBaggage.encode(null) == null
  }

  def "encode Boolean.TRUE returns 'true'"() {
    expect:
      FhubBaggage.encode(Boolean.TRUE) == "true"
  }

  def "encode Boolean.FALSE returns 'false'"() {
    expect:
      FhubBaggage.encode(Boolean.FALSE) == "false"
  }

  def "encode string with spaces uses plus encoding"() {
    expect:
      FhubBaggage.encode("hello world") == "hello+world"
  }

  def "encode BigDecimal returns its string representation"() {
    expect:
      FhubBaggage.encode(new BigDecimal("42.5")) == "42.5"
  }

  // --- decode ---

  def "decode null returns null"() {
    expect:
      FhubBaggage.decode(null) == null
  }

  def "decode plus-encoded string restores spaces"() {
    expect:
      FhubBaggage.decode("hello+world") == "hello world"
  }

  def "decode percent-encoded string"() {
    expect:
      FhubBaggage.decode("light%20blue") == "light blue"
  }

  // --- round-trips ---

  def "encode then decode is identity"() {
    given:
      def original = "value with spaces & special=chars!"
    expect:
      FhubBaggage.decode(FhubBaggage.encode(original)) == original
  }

  def "parse then build is identity for well-formed input"() {
    given:
      def fhub = "alpha=1,beta=hello%20world,gamma"
    expect:
      FhubBaggage.build(FhubBaggage.parse(fhub)) == fhub
  }
}
