package io.featurehub.netty;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.edge.EdgeRetryer;
import io.featurehub.client.utils.SdkVersion;
import io.featurehub.javascript.JavascriptObjectMapper;
import io.featurehub.sse.model.FeatureEnvironmentCollection;
import io.featurehub.sse.model.FeatureState;
import io.featurehub.sse.model.SSEResultState;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NettyRestClient implements EdgeService {
  private static final Logger log = LoggerFactory.getLogger(NettyRestClient.class);

  @NotNull private final InternalFeatureRepository repository;
  @NotNull private final FeatureHubConfig config;
  @NotNull private final EdgeRetryService edgeRetryService;
  @NotNull private final JavascriptObjectMapper mapper;
  @NotNull private final NioEventLoopGroup eventLoopGroup;
  @NotNull private final String featuresUrl;

  @Nullable private String xFeaturehubHeader;
  @NotNull private String xContextSha = "0";
  @Nullable private String etag = null;
  private long pollingInterval;
  private boolean makeRequests = true;
  private boolean stopped = false;

  private final String host;
  private final int port;
  private final boolean ssl;
  @Nullable private SslContext sslContext;

  final Pattern cacheControlRegex = Pattern.compile("max-age=(\\d+)");

  public NettyRestClient(@Nullable InternalFeatureRepository repository,
                         @NotNull FeatureHubConfig config,
                         @NotNull EdgeRetryService edgeRetryService,
                         int timeoutInSeconds,
                         boolean amPollingDelegate) {
    if (repository == null) {
      repository = (InternalFeatureRepository) config.getRepository();
    }

    this.repository = repository;
    this.mapper = repository.getJsonObjectMapper();
    this.config = config;
    this.edgeRetryService = edgeRetryService;
    this.pollingInterval = timeoutInSeconds;
    this.eventLoopGroup = new NioEventLoopGroup(2);

    this.featuresUrl = config.apiKeys().stream()
        .map(k -> "apiKey=" + k)
        .collect(Collectors.joining("&", config.baseUrl() + "/features?", ""));

    URI uri = URI.create(config.baseUrl());
    this.ssl = "https".equalsIgnoreCase(uri.getScheme());
    this.host = uri.getHost();
    this.port = uri.getPort() == -1 ? (ssl ? 443 : 80) : uri.getPort();

    if (ssl) {
      try {
        this.sslContext = SslContextBuilder.forClient().build();
      } catch (SSLException e) {
        log.error("[featurehub-sdk] Failed to create SSL context", e);
      }
    }
  }

  public NettyRestClient(@NotNull FeatureHubConfig config,
                         @NotNull EdgeRetryService edgeRetryService,
                         int timeoutInSeconds) {
    this(null, config, edgeRetryService, timeoutInSeconds, false);
  }

  public NettyRestClient(@NotNull FeatureHubConfig config) {
    this(null, config, EdgeRetryer.EdgeRetryerBuilder.anEdgeRetrier().rest().build(), 180, false);
  }

  @Override
  public Future<Readiness> poll() {
    if (!makeRequests || stopped) {
      return CompletableFuture.completedFuture(repository.getReadiness());
    }

    final CompletableFuture<Readiness> change = new CompletableFuture<>();
    final String requestPath = buildRequestPath();
    final String currentEtag = this.etag;
    final String currentHeader = this.xFeaturehubHeader;

    Bootstrap bootstrap = new Bootstrap();
    bootstrap.group(eventLoopGroup)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) edgeRetryService.getServerConnectTimeoutMs())
        .handler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) {
            if (ssl && sslContext != null) {
              ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), host, port));
            }
            ch.pipeline().addLast(new ReadTimeoutHandler(
                edgeRetryService.getServerReadTimeoutMs(), TimeUnit.MILLISECONDS));
            ch.pipeline().addLast(new HttpClientCodec());
            ch.pipeline().addLast(new HttpContentDecompressor());
            ch.pipeline().addLast(new HttpObjectAggregator(1_048_576));
            ch.pipeline().addLast(new RestResponseHandler(
                requestPath, host, port, currentHeader, currentEtag, change));
          }
        });

    bootstrap.connect(host, port).addListener((ChannelFuture f) -> {
      if (!f.isSuccess()) {
        log.error("[featurehub-sdk] Failed to connect to {}:{}", host, port, f.cause());
        repository.notify(SSEResultState.FAILURE, "polling");
        change.complete(Readiness.Failed);
      }
    });

    return change;
  }

  private String buildRequestPath() {
    URI base = URI.create(featuresUrl + "&contextSha=" + xContextSha);
    return base.getRawPath() + "?" + base.getRawQuery();
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, @NotNull String contextSha) {
    xFeaturehubHeader = newHeader;
    xContextSha = contextSha;
    return poll();
  }

  @Override
  public boolean needsContextChange(@Nullable String newHeader, @NotNull String contextSha) {
    return etag == null || repository.getReadiness() != Readiness.Ready
        || (!isClientEvaluation() && (newHeader != null && !newHeader.equals(xFeaturehubHeader)));
  }

  @Override
  public boolean isClientEvaluation() {
    return !config.isServerEvaluation();
  }

  @Override
  public boolean isStopped() {
    return stopped;
  }

  @Override
  public void close() {
    log.info("[featurehub-sdk] netty rest client closing.");
    makeRequests = false;
    eventLoopGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  @Override
  public long currentInterval() {
    return pollingInterval;
  }

  void onEtag(String newEtag) {
    this.etag = newEtag;
  }

  void onPollingInterval(long interval) {
    if (interval > 0) {
      this.pollingInterval = interval;
    }
  }

  void onUsageLimitReached() {
    log.info("[featurehub-sdk] - your SaaS account has reached the limit of the usage you have allowed yourself. Please increase usage in Billing or stop polling.");
    this.stopped = true;
  }

  void onTerminalError() {
    makeRequests = false;
    log.error("[featurehub-sdk] Server indicated an error with our requests making future ones pointless.");
    repository.notify(SSEResultState.FAILURE, "polling");
  }

  void onFeatures(List<FeatureEnvironmentCollection> environments) {
    List<FeatureState> states = new ArrayList<>();
    environments.forEach(e -> {
      if (e.getFeatures() != null) {
        e.getFeatures().forEach(f -> f.setEnvironmentId(e.getId()));
        states.addAll(e.getFeatures());
      }
    });
    log.trace("[featurehub-sdk] updating feature repository: {}", states);
    repository.updateFeatures(states, "polling");
  }

  void onParseFailure(String body, Exception ex) {
    log.error("[featurehub-sdk] Failed to process successful response from FH Edge server", ex);
    repository.notify(SSEResultState.FAILURE, "polling");
  }

  /** Per-request inbound handler — one instance per channel/poll call. */
  private final class RestResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private final String path;
    private final String host;
    private final int port;
    @Nullable private final String featurehubHeader;
    @Nullable private final String ifNoneMatch;
    private final CompletableFuture<Readiness> promise;

    RestResponseHandler(String path, String host, int port,
                        @Nullable String featurehubHeader, @Nullable String ifNoneMatch,
                        CompletableFuture<Readiness> promise) {
      this.path = path;
      this.host = host;
      this.port = port;
      this.featurehubHeader = featurehubHeader;
      this.ifNoneMatch = ifNoneMatch;
      this.promise = promise;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      DefaultFullHttpRequest req = new DefaultFullHttpRequest(
          HttpVersion.HTTP_1_1, HttpMethod.GET, path, Unpooled.EMPTY_BUFFER);
      req.headers().set(HttpHeaderNames.HOST, port == 80 || port == 443 ? host : host + ":" + port);
      req.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
      req.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
      if (featurehubHeader != null) {
        req.headers().set("x-featurehub", featurehubHeader);
      }
      if (ifNoneMatch != null) {
        req.headers().set(HttpHeaderNames.IF_NONE_MATCH, ifNoneMatch);
      }
      req.headers().set("X-SDK", SdkVersion.sdkVersionHeader("Java-Netty"));
      ctx.writeAndFlush(req);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
      try {
        log.trace("[featurehub-sdk] response code is {}", response.status().code());

        String cacheControl = response.headers().get(HttpHeaderNames.CACHE_CONTROL);
        if (cacheControl != null) {
          Matcher m = cacheControlRegex.matcher(cacheControl);
          if (m.find()) {
            try {
              long interval = Long.parseLong(m.group().split("=")[1]);
              onPollingInterval(interval);
            } catch (Exception ignored) {
            }
          }
        }

        String newEtag = response.headers().get(HttpHeaderNames.ETAG);
        if (newEtag != null) {
          onEtag(newEtag);
        }

        int code = response.status().code();
        if (code >= 200 && code < 300) {
          String body = response.content().toString(io.netty.util.CharsetUtil.UTF_8);
          try {
            List<FeatureEnvironmentCollection> envs = mapper.readFeatureCollection(body);
            onFeatures(envs);
            if (code == 236) {
              onUsageLimitReached();
            }
          } catch (Exception e) {
            onParseFailure(body, e);
          }
        } else if (code == 304) {
          // not modified — etag match, no action needed
        } else if (code == 400 || code == 401 || code == 403 || code == 404) {
          onTerminalError();
        }
        // 5xx: transient, no action — caller will retry on next poll interval
      } catch (Exception e) {
        log.error("[featurehub-sdk] Failed to parse response", e);
      } finally {
        promise.complete(repository.getReadiness());
        ctx.close();
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      log.error("[featurehub-sdk] Unable to call for features", cause);
      repository.notify(SSEResultState.FAILURE, "polling");
      promise.complete(Readiness.Failed);
      ctx.close();
    }
  }
}
