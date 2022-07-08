package io.featurehub.client;

import io.featurehub.sse.model.FeatureValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.featurehub.client.BaseClientContext.C_ID;
import static io.featurehub.client.GoogleAnalyticsApiClient.GA_VALUE;
import static io.featurehub.sse.model.FeatureValueType.*;
import static java.lang.Boolean.TRUE;
import static java.nio.charset.StandardCharsets.UTF_8;

public class GoogleAnalyticsCollector implements AnalyticsCollector {
  private static final Logger log = LoggerFactory.getLogger(GoogleAnalyticsCollector.class);
  private static final EnumMap<FeatureValueType, Function<FeatureState, String>> fsTypeToStringMapper = new EnumMap<>(FeatureValueType.class);
  private final String uaKey; // this must be provided
  private final GoogleAnalyticsApiClient client;
  private String cid; // if this is null, we will look for it in "other" and log an error if it isn't there

  public GoogleAnalyticsCollector(String uaKey, String cid, GoogleAnalyticsApiClient client) {
    if (uaKey == null) {
      throw new RuntimeException("UA id must be provided when using the Google Analytics Collector.");
    }
    if (client == null) {
      throw new RuntimeException("Unable to log any events as there is no client, please configure one.");
    }

    this.uaKey = uaKey;
    this.cid = cid;
    this.client = client;

    fsTypeToStringMapper.put(BOOLEAN, state -> state.getBoolean().equals(TRUE) ? "on" : "off");
    fsTypeToStringMapper.put(STRING, FeatureState::getString);
    fsTypeToStringMapper.put(NUMBER, state -> state.getNumber().toPlainString());
  }

  public void setCid(String cid) {
    this.cid = cid;
  }

  @Override
  public void logEvent(String action, Map<String, String> other, List<FeatureState> featureStateAtCurrentTime) {
    StringBuilder batchData = new StringBuilder();

    String finalCid = cid == null ? other.get(C_ID) : cid;

    if (finalCid == null) {
      log.error("There is no CID provided for GA, not logging any events.");
      return;
    }

    String ev;
    try {
      ev = (other != null && other.get(GA_VALUE) != null)
        ? ("&ev=" + URLEncoder.encode(other.get(GA_VALUE), UTF_8.name())) :
        "";

      String baseForEachLine =
        "v=1&tid=" + uaKey + "&cid=" + finalCid + "&t=event&ec=FeatureHub%20Event&ea=" + URLEncoder.encode(action,
          UTF_8.name()) + ev + "&el=";

      featureStateAtCurrentTime.forEach((fsh) -> {
        FeatureStateBase fs = (FeatureStateBase) fsh;
        if (!fs.isSet()) return;

        FeatureValueType type = fs.type();
        String line = fsTypeToStringMapper.containsKey(type) ? fsTypeToStringMapper.get(type).apply(fsh) : null;
        if (line == null) return;

        try {
          line = URLEncoder.encode(fsh.getKey() + " : " + line, UTF_8.name());
          batchData.append(baseForEachLine);
          batchData.append(line);
          batchData.append("\n");
        } catch (UnsupportedEncodingException e) { // can't happen
        }
      });
    } catch (UnsupportedEncodingException e) { // can't happen
    }

    if (batchData.length() > 0) {
      client.postBatchUpdate(batchData.toString());
    }
  }
}
