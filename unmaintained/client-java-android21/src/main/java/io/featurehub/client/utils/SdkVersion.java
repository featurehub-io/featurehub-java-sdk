package io.featurehub.client.utils;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.Manifest;

public class SdkVersion {
  private static final String VERSION = "FeatureHub-SDK-Version";
  private static String version = null;
  private static String constructedVariant = null;

  public static String SSE_API_VERSION = "1.1.2"; // while we are compiled against 1.1.3, we don't understand it

  public static String sdkVersionHeader(String variant) {
    if (constructedVariant == null) {
      constructedVariant = variant + "," + sdkVersion() + "," + SSE_API_VERSION;
    }

    return constructedVariant;
  }

  public static String sdkVersion() {
    if (version != null) {
      return version;
    }

    try {
      final Enumeration<URL> resources = SdkVersion.class.getClassLoader().getResources("META-INF/MANIFEST.MF");

      while (resources.hasMoreElements() && version == null) {
        try {
          final Manifest manifest = new Manifest(resources.nextElement().openStream());
          String val = manifest.getMainAttributes().getValue(VERSION);
          if (val != null) {
            version = val;
            return val;
          }
        } catch (Exception ignored) {}
      }
    } catch (IOException ignored) {
    }

    return "<UNKNOWN>";
  }
}
