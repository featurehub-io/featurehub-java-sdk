package io.featurehub.sdk.usageadapter.opentelemetry;

import org.jetbrains.annotations.Nullable;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared utilities for reading and writing the {@code fhub} OTel baggage entry.
 *
 * <p>The {@code fhub} format is a comma-separated, alphabetically sorted list of
 * {@code key=url-encoded-value} pairs. A key with no value (i.e. no {@code =} sign) represents
 * a feature whose raw value was {@code null}.
 */
final class FhubBaggage {

  private FhubBaggage() {}

  /**
   * Parses a {@code fhub} baggage string into a sorted map of {@code key → encoded-value}.
   * A key-only entry (no {@code =}) is stored with a {@code null} map value.
   * Returns an empty map for a null or blank input.
   */
  static TreeMap<String, String> parse(@Nullable String fhub) {
    TreeMap<String, String> result = new TreeMap<>();
    if (fhub == null || fhub.isEmpty()) {
      return result;
    }
    for (String entry : fhub.split(",")) {
      int eqIdx = entry.indexOf('=');
      if (eqIdx < 0) {
        result.put(entry, null);
      } else {
        result.put(entry.substring(0, eqIdx), entry.substring(eqIdx + 1));
      }
    }
    return result;
  }

  /**
   * Serialises a sorted map of {@code key → encoded-value} back to a {@code fhub} string.
   * A {@code null} map value produces a key-only entry (no {@code =}).
   */
  static String build(TreeMap<String, String> entries) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : entries.entrySet()) {
      if (sb.length() > 0) sb.append(',');
      sb.append(e.getKey());
      if (e.getValue() != null) {
        sb.append('=').append(e.getValue());
      }
    }
    return sb.toString();
  }

  /**
   * URL-encodes a raw feature value for inclusion in {@code fhub}.
   * Returns {@code null} when the raw value is {@code null} (producing a key-only entry).
   */
  static @Nullable String encode(@Nullable Object rawValue) {
    if (rawValue == null) {
      return null;
    }
    return URLEncoder.encode(rawValue.toString(), StandardCharsets.UTF_8);
  }

  /**
   * URL-decodes an encoded value read from {@code fhub}.
   * Returns {@code null} for a {@code null} input (key-only entry).
   */
  static @Nullable String decode(@Nullable String encoded) {
    if (encoded == null) {
      return null;
    }
    return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
  }
}
