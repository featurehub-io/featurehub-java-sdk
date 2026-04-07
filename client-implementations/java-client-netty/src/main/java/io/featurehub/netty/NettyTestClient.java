package io.featurehub.netty;

import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.TestApi;
import io.featurehub.client.TestApiResult;
import io.featurehub.client.utils.SdkVersion;
import io.featurehub.javascript.JavascriptObjectMapper;
import io.featurehub.sse.model.FeatureStateUpdate;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class NettyTestClient implements TestApi {
  private static final Logger log = LoggerFactory.getLogger(NettyTestClient.class);

  private final FeatureHubConfig config;
  private final JavascriptObjectMapper mapper;
  private final NioEventLoopGroup eventLoopGroup;

  private final String host;
  private final int port;
  private final boolean ssl;
  private final SslContext sslContext;

  public NettyTestClient(FeatureHubConfig config) {
    this.config = config;
    this.mapper = config.getInternalRepository().getJsonObjectMapper();
    this.eventLoopGroup = new NioEventLoopGroup(1);

    URI uri = URI.create(config.baseUrl());
    this.ssl = "https".equalsIgnoreCase(uri.getScheme());
    this.host = uri.getHost();
    this.port = uri.getPort() == -1 ? (ssl ? 443 : 80) : uri.getPort();

    SslContext ctx = null;
    if (ssl) {
      try {
        ctx = SslContextBuilder.forClient().build();
      } catch (SSLException e) {
        log.error("[featurehub-sdk] Failed to create SSL context for test client", e);
      }
    }
    this.sslContext = ctx;
  }

  @Override
  public @NotNull TestApiResult setFeatureState(String apiKey, @NotNull String featureKey, @NotNull FeatureStateUpdate featureStateUpdate) {
    String body;
    try {
      body = mapper.featureStateUpdateToString(featureStateUpdate);
    } catch (IOException e) {
      log.error("[featurehub-sdk] Failed to serialize FeatureStateUpdate", e);
      return new TestApiResult(500);
    }

    String path = "/" + apiKey + "/" + featureKey;
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

    CompletableFuture<Integer> responseFuture = new CompletableFuture<>();
    final SslContext finalSslCtx = sslContext;

    Bootstrap bootstrap = new Bootstrap();
    bootstrap.group(eventLoopGroup)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
        .handler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) {
            if (ssl && finalSslCtx != null) {
              ch.pipeline().addLast(finalSslCtx.newHandler(ch.alloc(), host, port));
            }
            ch.pipeline().addLast(new HttpClientCodec());
            ch.pipeline().addLast(new HttpContentDecompressor());
            ch.pipeline().addLast(new HttpObjectAggregator(65536));
            ch.pipeline().addLast(new TestResponseHandler(
                path, host, port, bodyBytes, responseFuture));
          }
        });

    bootstrap.connect(host, port).addListener(f -> {
      if (!f.isSuccess()) {
        log.error("[featurehub-sdk] Test client failed to connect", f.cause());
        responseFuture.complete(500);
      }
    });

    try {
      int statusCode = responseFuture.get(10, TimeUnit.SECONDS);
      return new TestApiResult(statusCode);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new TestApiResult(500);
    } catch (ExecutionException | TimeoutException e) {
      log.error("[featurehub-sdk] Test client request failed", e);
      return new TestApiResult(500);
    }
  }

  @Override
  public @NotNull TestApiResult setFeatureState(@NotNull String featureKey, @NotNull FeatureStateUpdate featureStateUpdate) {
    return setFeatureState(config.apiKey(), featureKey, featureStateUpdate);
  }

  @Override
  public void close() {
    eventLoopGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
  }

  private static final class TestResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private final String path;
    private final String host;
    private final int port;
    private final byte[] body;
    private final CompletableFuture<Integer> promise;

    TestResponseHandler(String path, String host, int port, byte[] body, CompletableFuture<Integer> promise) {
      this.path = path;
      this.host = host;
      this.port = port;
      this.body = body;
      this.promise = promise;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      DefaultFullHttpRequest req = new DefaultFullHttpRequest(
          HttpVersion.HTTP_1_1, HttpMethod.POST, path,
          Unpooled.wrappedBuffer(body));
      req.headers().set(HttpHeaderNames.HOST, port == 80 || port == 443 ? host : host + ":" + port);
      req.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
      req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
      req.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
      req.headers().set("X-SDK", SdkVersion.sdkVersionHeader("Java-Netty"));
      ctx.writeAndFlush(req);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
      promise.complete(response.status().code());
      ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      promise.complete(500);
      ctx.close();
    }
  }
}
