package io.featurehub.server.jersey;

import io.featurehub.client.ThreadLocalContext;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is an annotation scanner that is designed to allow you to annotate an API and
 * indicate that it will not be exposed unless a Flag is enabled. It expects the use of the
 * ThreadLocalContext to be setup and functioning.
 *
 * You should descend from this, add your own annotation for Priority and register it.
 */
public class FeatureRequiredApplicationEventListener implements ApplicationEventListener {
  @Override
  public void onEvent(ApplicationEvent event) {
  }

  @Override
  public RequestEventListener onRequest(RequestEvent requestEvent) {
    return new FeatureRequiredEvent();
  }

  static class FeatureInfo {
    public final String[] features;

    FeatureInfo(String[] features) {
      this.features = features;
    }
  }

  static Map<Method, FeatureInfo> featureInfo = new ConcurrentHashMap<>();

  static class FeatureRequiredEvent implements RequestEventListener {
    @Override
    public void onEvent(RequestEvent event) {
      if (event.getType() == RequestEvent.Type.REQUEST_MATCHED) {
        featureCheck(event);
      }
    }

    private void featureCheck(RequestEvent event) {
      FeatureInfo fi = featureInfo.computeIfAbsent(getMethod(event), this::extractFeatureInfo);

      // if any of the flags mentioned are OFF, return NOT_FOUND
      for (String feature : fi.features) {
        if (!ThreadLocalContext.getContext().feature(feature).isEnabled()) {
          event.getContainerRequest().abortWith(Response.status(Response.Status.NOT_FOUND).build());
          return;
        }
      }
    }

    private FeatureInfo extractFeatureInfo(Method m) {
      FeatureFlagEnabled fr = m.getDeclaredAnnotation(FeatureFlagEnabled.class);

      if (fr == null) {
        fr = m.getDeclaringClass().getAnnotation(FeatureFlagEnabled.class);

        if (fr == null) {
          for (Class<?> anInterface : m.getDeclaringClass().getInterfaces()) {
            fr = anInterface.getAnnotation(FeatureFlagEnabled.class);
            if (fr != null) {
              break;
            }
          }
        }
      }

      if (fr != null) {
        return new FeatureInfo(fr.features());
      }

      return NO_FEATURES_REQUIRED;
    }

    private static final FeatureInfo NO_FEATURES_REQUIRED = new FeatureInfo(new String[]{});

    Method getMethod(RequestEvent event) {
      return event.getUriInfo().getMatchedResourceMethod().getInvocable().getHandlingMethod();
    }
  }
}
