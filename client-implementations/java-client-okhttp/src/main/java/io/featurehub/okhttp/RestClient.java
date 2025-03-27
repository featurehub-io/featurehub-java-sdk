package io.featurehub.okhttp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.edge.EdgeRetryer;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RestClient implements EdgeService {
  private static final Logger log = LoggerFactory.getLogger(RestClient.class);
  @NotNull private final InternalFeatureRepository repository;
  @NotNull private final OkHttpClient client;
  private final EdgeRetryService edgeRetryService;
  private boolean makeRequests;
  @NotNull private final String url;
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
  private final boolean amPollingDelegate;
  @NotNull private final FeatureHubConfig config;
  @NotNull private final ExecutorService executorService;

  public RestClient(@Nullable InternalFeatureRepository repository,
                    @NotNull FeatureHubConfig config, @NotNull EdgeRetryService edgeRetryService, int timeoutInSeconds, boolean amPollingDelegate) {

    this.edgeRetryService = edgeRetryService;

    if (repository == null) {
      repository = (InternalFeatureRepository) config.getRepository();
    }

    this.amPollingDelegate = amPollingDelegate;
    this.repository = repository;

    this.client = new OkHttpClient.Builder()
      .connectTimeout(Duration.ofMillis(edgeRetryService.getServerConnectTimeoutMs()))
      .readTimeout(Duration.ofMillis(edgeRetryService.getServerReadTimeoutMs()))
      .build();

    this.config = config;
    this.pollingInterval = timeoutInSeconds;

    // ensure the poll has expired the first time we ask for it
    whenPollingCacheExpires = System.currentTimeMillis() - 100;

    this.clientSideEvaluation = !config.isServerEvaluation();
    this.makeRequests = true;
    executorService = makeExecutorService();

    url = config.baseUrl() + "/features?" + config.apiKeys().stream().map(u -> "apiKey=" + u).collect(Collectors.joining("&"));

    if (clientSideEvaluation) {
      checkForUpdates(null);
    }
  }

  protected ExecutorService makeExecutorService() {
    return Executors.newWorkStealingPool();
  }

  public RestClient(@NotNull FeatureHubConfig config,
        @NotNull EdgeRetryService edgeRetryService, int timeoutInSeconds) {
    this(null, config, edgeRetryService, timeoutInSeconds, false);
  }

  public RestClient(@NotNull FeatureHubConfig config) {
    this(null, config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().rest().build(), 180, false);
  }

  private final static TypeReference<List<FeatureEnvironmentCollection>> ref = new TypeReference<List<FeatureEnvironmentCollection>>(){};
  private boolean busy = false;
  private boolean headerChanged = false;
  private List<CompletableFuture<Readiness>> waitingClients = new ArrayList<>();

  protected Long now() {
    return System.currentTimeMillis();
  }

  public boolean checkForUpdates(@Nullable CompletableFuture<Readiness> change) {
    final boolean breakCache =
      amPollingDelegate || pollingInterval == 0 || (now() > whenPollingCacheExpires || headerChanged);
    final boolean ask = makeRequests && !busy && !stopped && breakCache;

    headerChanged = false;

    if (ask) {
      if (change != null) {
        // we are going to call, so we take a note of who we need to tell
        waitingClients.add(change);
      }

      busy = true;

      String url = this.url + "&contextSha=" + xContextSha;
      log.trace("request url is {}", url);
      Request.Builder reqBuilder = new Request.Builder().url(url);

      if (xFeaturehubHeader != null) {
        reqBuilder = reqBuilder.addHeader("x-featurehub", xFeaturehubHeader);
      }

      if (etag != null) {
        reqBuilder = reqBuilder.addHeader("if-none-match", etag);
      }

      reqBuilder.addHeader("X-SDK", SdkVersion.sdkVersionHeader("Java-OKHTTP"));

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

  protected void processFailure(@NotNull IOException e) {
    log.error("Unable to call for features", e);
    repository.notify(SSEResultState.FAILURE);
    busy = false;
    completeReadiness();
  }

  protected void processResponse(Response response) throws IOException {
    busy = false;

    log.trace("response code is {}", response.code());

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
        List<FeatureEnvironmentCollection> environments;

        try {
          environments = mapper.readValue(body.bytes(), ref);
        } catch (Exception e) {
          log.error("Failed to process successful response from FH Edge server", e);
          processFailure(new IOException(e));
          return;
        }

        List<FeatureState> states = new ArrayList<>();
        environments.forEach(e -> {
          if (e.getFeatures() != null) {
            e.getFeatures().forEach(f -> f.setEnvironmentId(e.getId()));
            states.addAll(e.getFeatures());
          }
        });

        log.trace("updating feature repository: {}", states);

        repository.updateFeatures(states);

        if (response.code() == 236) {
          this.stopped = true; // prevent any further requests
        }

        // reset the polling interval to prevent unnecessary polling
        if (pollingInterval > 0) {
          whenPollingCacheExpires = now() + (pollingInterval * 1000);
        }
      } else if (response.code() == 400 || response.code() == 404 || response.code() == 401 || response.code() == 403) {
        // 401 and 403 are possible because of misconfiguration
        makeRequests = false;
        log.error("Server indicated an error with our requests making future ones pointless.");
        repository.notify(SSEResultState.FAILURE);
      }
      // could be a 304 or 5xx as expected possible results
    } catch (Exception e) {
      log.error("Failed to parse response {}", response.code(), e);
    }

    completeReadiness(); // under all circumstances, unblock clients
  }

  boolean canMakeRequests() {
    return makeRequests && !stopped;
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

    makeRequests = false;

    if (client instanceof OkHttpClient) {
      ((OkHttpClient)client).dispatcher().executorService().shutdownNow();
    } else {
      log.warn("client is not OKHttpClient {}", client.getClass().getName());
    }

    executorService.shutdownNow();
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

  @Override
  public long currentInterval() {
    return pollingInterval;
  }

  public long getWhenPollingCacheExpires() {
    return whenPollingCacheExpires;
  }
}
