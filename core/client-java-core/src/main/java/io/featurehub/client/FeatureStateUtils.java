package io.featurehub.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class FeatureStateUtils {
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  static boolean changed(Object oldValue, Object newValue) {
    return ((oldValue != null && newValue == null) || (newValue != null && oldValue == null) ||
      (oldValue != null && !oldValue.equals(newValue)) || (newValue != null && !newValue.equals(oldValue)));
  }

  /**
   * Builds the canonical x-featurehub header (featurehub-protocol.yaml
   * encoding.evaluation_context.x_featurehub_header): each attribute with at least one non-null value becomes
   * encode(key) + "=" + encode(values joined by ","), the encoded strings are sorted by ordinal comparison and
   * joined with ",".
   *
   * @return the header, or null when no attribute has a value (there is then no header, and the contextSha is 0)
   */
  public static String generateXFeatureHubHeaderFromMap(Map<String, List<String>> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return null;
    }

    String header = attributes.entrySet().stream()
      .map(e -> {
        List<String> values = e.getValue() == null ? null :
          e.getValue().stream().filter(Objects::nonNull).collect(Collectors.toList());

        return (values == null || values.isEmpty()) ? null :
          encodeURIComponent(e.getKey()) + "=" + encodeURIComponent(String.join(",", values));
      })
      .filter(Objects::nonNull)
      .sorted()
      .collect(Collectors.joining(","));

    return header.isEmpty() ? null : header;
  }

  /**
   * The contextSha query parameter (featurehub-protocol.yaml encoding.evaluation_context.context_sha): the SHA-256
   * of the x-featurehub header as 64 lowercase hex characters, or "0" when there is no header.
   */
  public static String contextSha(String header) {
    if (header == null || header.isEmpty()) {
      return "0";
    }

    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(header.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(2 * hash.length);

      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0x0f, 16)).append(Character.forDigit(b & 0x0f, 16));
      }

      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * Encodes as JavaScript's encodeURIComponent does, which the protocol requires of every SDK: UTF-8, then every
   * byte except A-Z a-z 0-9 - _ . ! ~ * ' ( ) as %XX with uppercase hex. This is not form encoding (URLEncoder
   * writes a space as "+" and encodes ~ ! ' ( )), so URLEncoder must not be used.
   */
  static String encodeURIComponent(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder encoded = new StringBuilder(bytes.length);

    for (byte b : bytes) {
      int c = b & 0xff;

      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
        c == '-' || c == '_' || c == '.' || c == '!' || c == '~' || c == '*' || c == '\'' || c == '(' || c == ')') {
        encoded.append((char) c);
      } else {
        encoded.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0f]);
      }
    }

    return encoded.toString();
  }
}
