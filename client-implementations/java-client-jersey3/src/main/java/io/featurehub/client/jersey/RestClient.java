package io.featurehub.client.jersey;

import cd.connect.openapi.support.ApiClient;
import cd.connect.openapi.support.ApiResponse;
import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.edge.EdgeStatus;
import io.featurehub.sse.model.FeatureEnvironmentCollection;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.SSEResultState;
import jakarta.ws.rs.RedirectionException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.filter.EncodingFeature;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.message.DeflateEncoder;
import org.glassfish.jersey.message.GZipEncoder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestClient implements EdgeService {
  private static final Logger log = LoggerFactory.getLogger(RestClient.class);
  @NotNull
  private final InternalFeatureRepository repository;
  @NotNull private final FeatureService client;
  @NotNull private final EdgeRetryService edgeRetryer;
  @Nullable
  private String xFeaturehubHeader;
  // used for breaking the cache
  @NotNull
  private String xContextSha = "0";
  // once stopped (the server told us to, our requests are pointless or we are closed) we make no more requests. Set
  // and read on different threads, so volatile.
  private volatile boolean stopped = false;
  @Nullable
  private String etag = null;
  private long pollingInterval;

  @NotNull private final FeatureHubConfig config;

  /**
   * a Rest client.
   *
   * @param repository - expected to be null, but able to be passed in because of special use cases
   * @param client - expected to be null, but able to be passed in because of testing
   * @param config - FH config
   * @param edgeRetryer - used for timeouts
   * @param stateTimeoutInSeconds - use 0 for once off and for when using an actual timer
   */
  public RestClient(@Nullable InternalFeatureRepository repository,
                    @Nullable FeatureService client,
                    @NotNull FeatureHubConfig config,
                    @NotNull EdgeRetryService edgeRetryer,
                    int stateTimeoutInSeconds) {
    this.edgeRetryer = edgeRetryer;
    if (repository == null) {
      repository = (InternalFeatureRepository) config.getRepository();
    }

    this.repository = repository;
    this.client = client == null ? makeClient(config) : client;
    this.config = config;
    this.pollingInterval = stateTimeoutInSeconds;
  }

  @NotNull protected FeatureService makeClient(FeatureHubConfig config) {
    Client client = ClientBuilder.newBuilder()
      .property(ClientProperties.CONNECT_TIMEOUT, edgeRetryer.getServerConnectTimeoutMs())
      .property(ClientProperties.READ_TIMEOUT, edgeRetryer.getServerReadTimeoutMs())
      .register(JacksonFeature.class)
      // ask for compressed responses and transparently decompress them
      .register(new EncodingFeature(GZipEncoder.class, DeflateEncoder.class))
      .build();

    return new FeatureServiceImpl(new ApiClient(client, config.baseUrl()));
  }

  protected Long now() {
    return System.currentTimeMillis();
  }

  public Future<Readiness> poll() {
    if (stopped) {
      log.trace("[featurehub-sdk] not polling, further requests are pointless or we are closed");
      return CompletableFuture.completedFuture(repository.getReadiness());
    }

    final CompletableFuture<Readiness> change = new CompletableFuture<>();

    Map<String, String> headers = new HashMap<>();
    if (xFeaturehubHeader != null) {
      headers.put("x-featurehub", xFeaturehubHeader);
    }

    if (etag != null) {
      headers.put("if-none-match", etag);
    }

    try {
      final ApiResponse<List<FeatureEnvironmentCollection>> response = client.getFeatureStates(config.apiKeys(),
        xContextSha, headers);
      processResponse(response);
    } catch (RedirectionException re) {
      // 304 not modified is fine
      if (re.getResponse().getStatus() != 304) {
        processFailure(re);
      }
    } catch (WebApplicationException wae) {
      // depending on the client, an error status may be thrown rather than returned
      if (wae.getResponse() != null && EdgeStatus.requestsArePointless(wae.getResponse().getStatus())) {
        stopPointlessRequests();
      } else {
        processFailure(wae);
      }
    } catch (Exception e) {
      processFailure(e);
    }

    change.complete(repository.getReadiness());

    return change;
  }

  protected @Nullable String getEtag() {
    return etag;
  }

  protected void setEtag(@Nullable String etag) {
    this.etag = etag;
  }

  @Nullable public Long getPollingInterval() {
    return pollingInterval;
  }

  final Pattern cacheControlRegex = Pattern.compile("max-age=(\\d+)");

  public void processCacheControlHeader(@NotNull String cacheControlHeader) {
    final Matcher matcher = cacheControlRegex.matcher(cacheControlHeader);
    if (matcher.find()) {
      final String interval = matcher.group().split("=")[1];
      try {
        long newInterval = Long.parseLong(interval);
        if (newInterval > 0) {
          this.pollingInterval = newInterval;
        }
      } catch (Exception e) {
        // ignored
      }
    }
  }

  /**
   * The request failed in a way that may well go away (e.g. we couldn't connect, too many requests, a 5xx), so we keep
   * whatever readiness we have and try again on the next poll.
   */
  protected void processFailure(@NotNull Exception e) {
    log.error("Unable to call for features", e);
  }

  protected void processResponse(ApiResponse<List<FeatureEnvironmentCollection>> response) throws IOException {
    log.trace("response code is {}", response.getStatusCode());

    // check the cache-control for the max-age
    final String cacheControlHeader = response.getResponse().getHeaderString("cache-control");
    if (cacheControlHeader != null) {
      processCacheControlHeader(cacheControlHeader);
    }

    // preserve the etag header if it exists
    final String etagHeader = response.getResponse().getHeaderString("etag");
    if (etagHeader != null) {
      this.etag = etagHeader;
    }

    if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
      List<FeatureState> states = new ArrayList<>();
      response.getData().forEach(e -> {
        if (e.getFeatures() != null) {
          e.getFeatures().forEach(f -> f.setEnvironmentId(e.getId()));
          states.addAll(e.getFeatures());
        }
      });

      log.trace("updating feature repository: {}", states);

      repository.updateFeatures(states, "polling");

      if (response.getStatusCode() == 236) {
        this.stopped = true; // prevent any further requests
      }
    } else if (EdgeStatus.requestsArePointless(response.getStatusCode())) {
      stopPointlessRequests();
    } else if (response.getStatusCode() >= 500) {
      log.trace("maybe server is down?");
    }
  }

  private void stopPointlessRequests() {
    stopped = true;
    log.error("Server indicated an error with our requests making future ones pointless.");
    repository.notify(SSEResultState.FAILURE, "polling");
  }

  /**
   * Stopped if the server has told us to stop, our requests are pointless or we have been closed. Polling stops once
   * we are stopped.
   */
  public boolean isStopped() { return stopped; }

  @Override
  public boolean needsContextChange(String newHeader, String contextSha) {
    return etag == null || repository.getReadiness() != Readiness.Ready || (!isClientEvaluation() && (newHeader != null && !newHeader.equals(xFeaturehubHeader)));
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, @NotNull String contextSha) {
    xFeaturehubHeader = newHeader;
    xContextSha = contextSha;

    return poll();
  }

  @Override
  public boolean isClientEvaluation() {
    return !config.isServerEvaluation();
  }

  @Override
  public void close() {
    stopped = true;
    edgeRetryer.close();

    log.info("featurehub client closed.");
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }


  @Override
  public long currentInterval() {
    return pollingInterval;
  }
}
