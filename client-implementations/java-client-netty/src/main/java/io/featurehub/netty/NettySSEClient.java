package io.featurehub.netty;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.client.edge.EdgeConnectionState;
import io.featurehub.client.edge.EdgeReconnector;
import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.utils.SdkVersion;
import io.featurehub.sse.model.SSEResultState;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.CharsetUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class NettySSEClient implements EdgeService, EdgeReconnector {
  private static final Logger log = LoggerFactory.getLogger(NettySSEClient.class);

  @NotNull private final InternalFeatureRepository repository;
  @NotNull private final FeatureHubConfig config;
  @NotNull private final EdgeRetryService retryer;
  @NotNull private final NioEventLoopGroup eventLoopGroup;

  @Nullable private Channel channel;
  @Nullable private String xFeaturehubHeader;

  private final String host;
  private final int port;
  private final String realtimePath;
  private final boolean ssl;
  @Nullable private SslContext sslContext;

  private final List<CompletableFuture<Readiness>> waitingClients = new ArrayList<>();

  public NettySSEClient(@Nullable InternalFeatureRepository repository,
                        @NotNull FeatureHubConfig config,
                        @NotNull EdgeRetryService retryer) {
    this.repository = repository == null
        ? (InternalFeatureRepository) config.getRepository()
        : repository;
    this.config = config;
    this.retryer = retryer;
    this.eventLoopGroup = new NioEventLoopGroup(2);

    URI uri = URI.create(config.getRealtimeUrl());
    this.ssl = "https".equalsIgnoreCase(uri.getScheme())
        || "wss".equalsIgnoreCase(uri.getScheme());
    this.host = uri.getHost();
    this.port = uri.getPort() == -1 ? (ssl ? 443 : 80) : uri.getPort();
    this.realtimePath = uri.getRawPath()
        + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");

    if (ssl) {
      try {
        this.sslContext = SslContextBuilder.forClient().build();
      } catch (SSLException e) {
        log.error("[featurehub-sdk] Failed to create SSL context", e);
      }
    }
  }

  public NettySSEClient(@NotNull FeatureHubConfig config, @NotNull EdgeRetryService retryer) {
    this(null, config, retryer);
  }

  @Override
  public Future<Readiness> poll() {
    if (channel != null && channel.isActive()) {
      return CompletableFuture.completedFuture(repository.getReadiness());
    }
    initConnection();
    return CompletableFuture.completedFuture(repository.getReadiness());
  }

  @Override
  public long currentInterval() {
    return 0;
  }

  private void initConnection() {
    final String currentHeader = this.xFeaturehubHeader;
    final NettySSEClient self = this;

    Bootstrap bootstrap = new Bootstrap();
    bootstrap.group(eventLoopGroup)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) retryer.getServerConnectTimeoutMs())
        .handler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) {
            if (ssl && sslContext != null) {
              ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), host, port));
            }
            ch.pipeline().addLast(new ReadTimeoutHandler(
                retryer.getServerReadTimeoutMs(), TimeUnit.MILLISECONDS));
            ch.pipeline().addLast(new HttpClientCodec());
            ch.pipeline().addLast(new HttpContentDecompressor());
            ch.pipeline().addLast(new SseConnectionHandler(self, currentHeader));
          }
        });

    bootstrap.connect(host, port).addListener(f -> {
      if (!f.isSuccess()) {
        log.error("[featurehub-sdk] Failed to connect SSE to {}:{}", host, port, f.cause());
        if (repository.getReadiness() == Readiness.NotReady) {
          repository.notify(SSEResultState.FAILURE);
        }
        retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, self);
      } else {
        channel = ((io.netty.channel.ChannelFuture) f).channel();
      }
    });
  }

  @Override
  public @NotNull Future<Readiness> contextChange(String newHeader, String contextSha) {
    final CompletableFuture<Readiness> change = new CompletableFuture<>();

    if (config.isServerEvaluation()
        && ((newHeader != null && !newHeader.equals(xFeaturehubHeader))
            || (xFeaturehubHeader != null && !xFeaturehubHeader.equals(newHeader)))) {

      log.warn("[featurehub-sdk] please only use server evaluated keys with SSE with one repository per SSE client.");
      xFeaturehubHeader = newHeader;

      if (channel != null && channel.isActive()) {
        channel.close();
        channel = null;
      }
    }

    if (channel == null || !channel.isActive()) {
      synchronized (waitingClients) {
        waitingClients.add(change);
      }
      poll();
    } else {
      change.complete(repository.getReadiness());
    }

    return change;
  }

  @Override
  public boolean isClientEvaluation() {
    return !config.isServerEvaluation();
  }

  @Override
  public boolean isStopped() {
    return retryer.isStopped();
  }

  @Override
  public void close() {
    retryer.close();

    if (channel != null && channel.isActive()) {
      log.info("[featurehub-sdk] closing SSE connection");
      channel.close();
      channel = null;
    }

    eventLoopGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  @Override
  public void reconnect() {
    channel = null;
    initConnection();
  }

  // ---- callbacks from SseConnectionHandler ----

  void onSseEvent(String type, @Nullable String data) {
    try {
      final SSEResultState state = retryer.fromValue(type);
      if (state == null) {
        return;
      }

      log.trace("[featurehub-sdk] decode packet {}:{}", type, data);

      if (state == SSEResultState.CONFIG) {
        retryer.edgeConfigInfo(data);
      } else if (data != null) {
        retryer.convertSSEState(state, data, repository, config.getEnvironmentId());
      }

      if (state == SSEResultState.FEATURES) {
        retryer.edgeResult(EdgeConnectionState.SUCCESS, this);
      }

      if (state == SSEResultState.FAILURE) {
        retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, this);
      }

      if (!waitingClients.isEmpty() && state != SSEResultState.ACK && state != SSEResultState.CONFIG) {
        List<CompletableFuture<Readiness>> toComplete;
        synchronized (waitingClients) {
          toComplete = new ArrayList<>(waitingClients);
          waitingClients.clear();
        }
        toComplete.forEach(wc -> wc.complete(repository.getReadiness()));
      }
    } catch (Exception e) {
      log.error("[featurehub-sdk] failed to decode packet {}:{}", type, data, e);
    }
  }

  void onConnectionClosed(boolean serverSaidBye) {
    channel = null;

    if (repository.getReadiness() == Readiness.NotReady) {
      repository.notify(SSEResultState.FAILURE);
    }

    retryer.edgeResult(
        serverSaidBye ? EdgeConnectionState.SERVER_SAID_BYE : EdgeConnectionState.SERVER_WAS_DISCONNECTED,
        this);
  }

  void onConnectionError(Throwable cause) {
    channel = null;

    if (repository.getReadiness() == Readiness.NotReady) {
      log.trace("[featurehub-sdk] failed to connect to {} - {}", config.baseUrl(), cause.getMessage());
      repository.notify(SSEResultState.FAILURE);
    }

    if (cause instanceof ConnectException) {
      retryer.edgeResult(EdgeConnectionState.CONNECTION_FAILURE, this);
    } else if (cause instanceof ReadTimeoutException) {
      retryer.edgeResult(EdgeConnectionState.SERVER_READ_TIMEOUT, this);
    } else {
      retryer.edgeResult(EdgeConnectionState.SERVER_WAS_DISCONNECTED, this);
    }
  }

  /**
   * Handles the long-lived SSE HTTP connection for a single connect/stream cycle.
   * A new instance is created per connection attempt.
   */
  static final class SseConnectionHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(SseConnectionHandler.class);

    private final NettySSEClient parent;
    private final String path;
    private final String host;
    private final int port;
    @Nullable private final String featurehubHeader;

    private final StringBuilder sseBuffer = new StringBuilder();
    private boolean connectionSaidBye = false;
    private boolean closedReported = false;

    SseConnectionHandler(NettySSEClient parent, @Nullable String featurehubHeader) {
      this.parent = parent;
      this.featurehubHeader = featurehubHeader;

      URI uri = URI.create(parent.config.getRealtimeUrl());
      this.host = uri.getHost();
      this.port = uri.getPort() == -1 ? (parent.ssl ? 443 : 80) : uri.getPort();
      this.path = parent.realtimePath;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      log.trace("[featurehub-sdk] SSE channel active, sending GET {}", path);

      DefaultFullHttpRequest req = new DefaultFullHttpRequest(
          HttpVersion.HTTP_1_1, HttpMethod.GET, path, Unpooled.EMPTY_BUFFER);
      req.headers().set(HttpHeaderNames.HOST, port == 80 || port == 443 ? host : host + ":" + port);
      req.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
      req.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream");
      req.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
      req.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
      if (featurehubHeader != null) {
        req.headers().set("x-featurehub", featurehubHeader);
      }
      req.headers().set("X-SDK", SdkVersion.sdkVersionHeader("Java-Netty-SSE"));
      ctx.writeAndFlush(req);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      try {
        if (msg instanceof HttpResponse) {
          HttpResponse resp = (HttpResponse) msg;
          log.trace("[featurehub-sdk] SSE response status: {}", resp.status());
          // Non-200 will result in no events; channelInactive will fire and report the disconnect
        }
        if (msg instanceof HttpContent) {
          HttpContent content = (HttpContent) msg;
          try {
            String text = content.content().toString(CharsetUtil.UTF_8);
            sseBuffer.append(text);
            parseSseEvents();
          } finally {
            content.release();
          }
          if (msg instanceof LastHttpContent) {
            // Server closed the response body — treat as disconnect
            reportClosed();
            ctx.close();
          }
        }
      } catch (Exception e) {
        log.error("[featurehub-sdk] error in SSE channel read", e);
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      log.trace("[featurehub-sdk] SSE channel inactive");
      reportClosed();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      log.trace("[featurehub-sdk] SSE channel exception: {}", cause.getMessage());
      reportError(cause);
      ctx.close();
    }

    private void reportClosed() {
      if (!closedReported) {
        closedReported = true;
        parent.onConnectionClosed(connectionSaidBye);
      }
    }

    private void reportError(Throwable cause) {
      if (!closedReported) {
        closedReported = true;
        parent.onConnectionError(cause);
      }
    }

    private void parseSseEvents() {
      String buf = sseBuffer.toString();
      int idx;
      while ((idx = buf.indexOf("\n\n")) != -1) {
        String block = buf.substring(0, idx);
        buf = buf.substring(idx + 2);
        if (!block.trim().isEmpty()) {
          parseSseBlock(block);
        }
      }
      sseBuffer.setLength(0);
      sseBuffer.append(buf);
    }

    private void parseSseBlock(String block) {
      String type = null;
      String data = null;

      for (String line : block.split("\n")) {
        if (line.startsWith("event:")) {
          type = line.substring("event:".length()).trim();
        } else if (line.startsWith("data:")) {
          data = line.substring("data:".length()).trim();
        }
      }

      if (type != null) {
        if ("bye".equalsIgnoreCase(type)) {
          connectionSaidBye = true;
        }
        parent.onSseEvent(type, data);
      }
    }
  }
}
