package io.featurehub.android;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.FeatureStore;
import io.featurehub.client.Readyness;
import io.featurehub.client.utils.SdkVersion;
import io.featurehub.sse.model.FeatureEnvironmentCollection;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.SSEResultState;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FeatureHubClient implements EdgeService {
  private static final Logger log = LoggerFactory.getLogger(FeatureHubClient.class);
  private final FeatureStore repository;
  private final Call.Factory client;
  private boolean makeRequests;
  private final String url;
  private final ObjectMapper mapper = new ObjectMapper();
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
  private final FeatureHubConfig config;
  private final ExecutorService executorService;

  public FeatureHubClient(String host, Collection<String> sdkUrls, FeatureStore repository,
                          Call.Factory client, FeatureHubConfig config, int timeoutInSeconds) {
    this.repository = repository;
    this.client = client;
    this.config = config;
    this.pollingInterval = timeoutInSeconds;

    // ensure the poll has expired the first time we ask for it
    whenPollingCacheExpires = System.currentTimeMillis() - 100;

    if (host != null && sdkUrls != null && !sdkUrls.isEmpty()) {
      this.clientSideEvaluation = sdkUrls.stream().anyMatch(FeatureHubConfig::sdkKeyIsClientSideEvaluated);

      this.makeRequests = true;

      executorService = makeExecutorService();

      url = host + "/features?" + sdkUrls.stream().map(u -> "apiKey=" + u).collect(Collectors.joining("&"));

      if (clientSideEvaluation) {
        checkForUpdates();
      }
    } else {
      throw new RuntimeException("FeatureHubClient initialized without any sdkUrls");
    }
  }

  protected ExecutorService makeExecutorService() {
    return Executors.newWorkStealingPool();
  }

  public FeatureHubClient(String host, Collection<String> sdkUrls, FeatureStore repository, FeatureHubConfig config,
                          int timeoutInSeconds) {
    this(host, sdkUrls, repository, (Call.Factory) new OkHttpClient(), config, timeoutInSeconds);
  }

  public FeatureHubClient(String host, Collection<String> sdkUrls, FeatureStore repository, FeatureHubConfig config) {
    this(host, sdkUrls, repository, (Call.Factory) new OkHttpClient(), config, 180);
  }

  private final static TypeReference<List<FeatureEnvironmentCollection>> ref = new TypeReference<List<FeatureEnvironmentCollection>>(){};
  private boolean busy = false;
  private boolean triggeredAtLeastOnce = false;
  private List<CompletableFuture<Readyness>> waitingClients = new ArrayList<>();

  protected Long now() {
    return System.currentTimeMillis();
  }

  public boolean checkForUpdates() {
    final boolean ask = makeRequests && !busy && !stopped && (now() > whenPollingCacheExpires);

    if (ask) {
      busy = true;
      triggeredAtLeastOnce = true;

      String url = this.url + "&contextSha=" + xContextSha;
      log.debug("Url is {}", url);
      Request.Builder reqBuilder = new Request.Builder().url(url);

      if (xFeaturehubHeader != null) {
        reqBuilder = reqBuilder.addHeader("x-featurehub", xFeaturehubHeader);
      }

      if (etag != null) {
        reqBuilder = reqBuilder.addHeader("if-none-match", etag);
      }

      reqBuilder.addHeader("X-SDK", SdkVersion.sdkVersionHeader("Java-Android21"));

      Request request = reqBuilder.build();

      Call call = client.newCall(request);
      call.enqueue(new Callback() {
        @Override
        public void onFailure(@NotNull Call call, @NotNull IOException e) {
          processFailure(e);
        }

        @Override
        public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
          processResponse(response);
        }
      });
    }

    return ask;
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
        Long newInterval = Long.parseLong(interval);
        if (newInterval > 0) {
          this.pollingInterval = newInterval;
        }
      } catch (Exception e) {
        // ignored
      }
    }
  }

  protected void processFailure(@NotNull IOException e) {
    log.error("Unable to call for features", e);
    repository.notify(SSEResultState.FAILURE, null);
    busy = false;
    completeReadiness();
  }

  protected void processResponse(Response response) throws IOException {
    busy = false;

    // check the cache-control for the max-age
    final String cacheControlHeader = response.header("cache-control");
    if (cacheControlHeader != null) {
      processCacheControlHeader(cacheControlHeader);
    }

    // preserve the etag header if it exists
    final String etagHeader = response.header("etag");
    if (etagHeader != null) {
      this.etag = etagHeader;
    }

    try (ResponseBody body = response.body()) {
      if (response.isSuccessful() && body != null) {
        List<FeatureEnvironmentCollection> environments = mapper.readValue(body.bytes(), ref);
        log.debug("updating feature repository: {}", environments);

        List<FeatureState> states = new ArrayList<>();
        environments.forEach(e -> {
          if (e.getFeatures() != null) {
            e.getFeatures().forEach(f -> f.setEnvironmentId(e.getId()));
            states.addAll(e.getFeatures());
          }
        });

        repository.notify(states);

        if (response.code() == 236) {
          this.stopped = true; // prevent any further requests
        }

        // reset the polling interval to prevent unnecessary polling
        if (pollingInterval > 0) {
          whenPollingCacheExpires = now() + (pollingInterval * 1000);
        }
      } else if (response.code() == 400 || response.code() == 404) {
        makeRequests = false;
        log.error("Server indicated an error with our requests making future ones pointless.");
        repository.notify(SSEResultState.FAILURE, null);
      }
    }

    completeReadiness();
  }

  boolean canMakeRequests() {
    return makeRequests && !stopped;
  }

  boolean isStopped() { return stopped; }

  private void completeReadiness() {
    List<CompletableFuture<Readyness>> current = waitingClients;
    waitingClients = new ArrayList<>();
    current.forEach(c -> {
      try {
        c.complete(repository.getReadyness());
      } catch (Exception e) {
        log.error("Unable to complete future", e);
      }
    });
  }

  @Override
  public @NotNull Future<Readyness> contextChange(@Nullable String newHeader, @NotNull String contextSha) {
    final CompletableFuture<Readyness> change = new CompletableFuture<>();

    if (!triggeredAtLeastOnce || (newHeader != null && !newHeader.equals(xFeaturehubHeader))) {

      xFeaturehubHeader = newHeader;
      xContextSha = contextSha;

      if (checkForUpdates() || busy) {
        waitingClients.add(change);
      } else {
        change.complete(repository.getReadyness());
      }
    } else {
      change.complete(repository.getReadyness());
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

    makeRequests = false;

    if (client instanceof OkHttpClient) {
      ((OkHttpClient)client).dispatcher().executorService().shutdownNow();
    }

    executorService.shutdownNow();
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  @Override
  public boolean isRequiresReplacementOnHeaderChange() {
    return false;
  }

  @Override
  public void poll() {
    checkForUpdates();
  }

  public long getWhenPollingCacheExpires() {
    return whenPollingCacheExpires;
  }
}
