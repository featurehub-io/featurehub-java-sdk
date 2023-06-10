package io.featurehub.client.jersey;

import cd.connect.openapi.support.ApiClient;
import cd.connect.openapi.support.ApiResponse;
import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.sse.model.FeatureEnvironmentCollection;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.SSEResultState;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RestClient implements EdgeService {
  private static final Logger log = LoggerFactory.getLogger(RestClient.class);
  @NotNull
  private final InternalFeatureRepository repository;
  @NotNull private final FeatureService client;
  @Nullable
  private String xFeaturehubHeader;
  // used for breaking the cache
  @NotNull
  private String xContextSha = "0";
  private boolean stopped = false;
  @Nullable
  private String etag = null;
  private long pollingInterval;

  private long whenPollingCacheExpires;
  private final boolean clientSideEvaluation;
  @NotNull private final FeatureHubConfig config;

  /**
   * a Rest client.
   *
   * @param repository - expected to be null, but able to be passed in because of special use cases
   * @param client - expected to be null, but able to be passed in because of testing
   * @param config - FH config
   * @param timeoutInSeconds - use 0 for once off and for when using an actual timer
   */
  public RestClient(@Nullable InternalFeatureRepository repository,
                    @Nullable FeatureService client,
                    @NotNull FeatureHubConfig config,
                    int timeoutInSeconds) {
    if (repository == null) {
      repository = (InternalFeatureRepository) config.getRepository();
    }

    this.repository = repository;
    this.client = client == null ? makeClient(config) : client;
    this.config = config;
    this.pollingInterval = timeoutInSeconds;

    // ensure the poll has expired the first time we ask for it
    whenPollingCacheExpires = System.currentTimeMillis() - 100;

    this.clientSideEvaluation = !config.isServerEvaluation();

    if (clientSideEvaluation) {
      checkForUpdates(null);
    }
  }

  @NotNull protected FeatureService makeClient(FeatureHubConfig config) {
    Client client = ClientBuilder.newBuilder()
      .register(JacksonFeature.class).build();

    return new FeatureServiceImpl(new ApiClient(client, config.baseUrl()));
  }

  public RestClient(@NotNull FeatureHubConfig config,
                    int timeoutInSeconds) {
    this(null, null, config, timeoutInSeconds);
  }

  public RestClient(@Nullable InternalFeatureRepository repository, @NotNull FeatureHubConfig config) {
    this(repository, null, config, 180);
  }

  public RestClient(@NotNull FeatureHubConfig config) {
    this(null, null, config, 180);
  }

  private boolean busy = false;
  private boolean headerChanged = false;
  private List<CompletableFuture<Readiness>> waitingClients = new ArrayList<>();

  protected Long now() {
    return System.currentTimeMillis();
  }

  public boolean checkForUpdates(@Nullable CompletableFuture<Readiness> change) {
    final boolean breakCache = pollingInterval == 0 || (now() > whenPollingCacheExpires || headerChanged);
    final boolean ask = !busy && !stopped && breakCache;

    headerChanged = false;

    if (ask) {
      if (change != null) {
        // we are going to call, so we take a note of who we need to tell
        waitingClients.add(change);
      }

      busy = true;

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
      } catch (Exception e) {
        processFailure(e);
      }
    }

    return ask;
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

  protected void processFailure(@NotNull Exception e) {
    log.error("Unable to call for features", e);
    repository.notify(SSEResultState.FAILURE);
    busy = false;
    completeReadiness();
  }

  protected void processResponse(ApiResponse<List<FeatureEnvironmentCollection>> response) throws IOException {
    busy = false;

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

      repository.updateFeatures(states);
      completeReadiness();

      if (response.getStatusCode() == 236) {
        this.stopped = true; // prevent any further requests
      }

      // reset the polling interval to prevent unnecessary polling
      if (pollingInterval > 0) {
        whenPollingCacheExpires = now() + (pollingInterval * 1000);
      }
    } else if (response.getStatusCode() == 400 || response.getStatusCode() == 404) {
      stopped = true;
      log.error("Server indicated an error with our requests making future ones pointless.");
      repository.notify(SSEResultState.FAILURE);
      completeReadiness();
    } else if (response.getStatusCode() >= 500) {
      completeReadiness(); // we haven't changed anything, but we have to unblock clients as we can't just hang
    }
  }

  public boolean isStopped() { return stopped; }

  private void completeReadiness() {
    List<CompletableFuture<Readiness>> current = waitingClients;
    waitingClients = new ArrayList<>();
    current.forEach(c -> {
      try {
        c.complete(repository.getReadiness());
      } catch (Exception e) {
        log.error("Unable to complete future", e);
      }
    });
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, @NotNull String contextSha) {
    final CompletableFuture<Readiness> change = new CompletableFuture<>();

    headerChanged = (newHeader != null && !newHeader.equals(xFeaturehubHeader));

    xFeaturehubHeader = newHeader;
    xContextSha = contextSha;

    // if there is already another change running, you are out of luck
    if (busy) {
      waitingClients.add(change);
    } else if (!checkForUpdates(change)) {
      change.complete(repository.getReadiness());
    }

    return change;
  }

  @Override
  public boolean isClientEvaluation() {
    return clientSideEvaluation;
  }

  @Override
  public void close() {
    log.info("featurehub client closed.");
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  @Override
  public Future<Readiness> poll() {
    final CompletableFuture<Readiness> change = new CompletableFuture<>();

    if (busy) {
      waitingClients.add(change);
    } else if (!checkForUpdates(change)) {
      // not even planning to ask
      change.complete(repository.getReadiness());
    }

    return change;
  }

  public long getWhenPollingCacheExpires() {
    return whenPollingCacheExpires;
  }
}
