package io.featurehub.client.jersey;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.client.edge.EdgeReconnector;
import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.edge.StreamingConnectionTracker;
import io.featurehub.client.utils.SdkVersion;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.sse.EventInput;
import org.glassfish.jersey.media.sse.InboundEvent;
import org.glassfish.jersey.media.sse.SseFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

public class JerseySSEClient implements EdgeService, EdgeReconnector {
  private static final Logger log = LoggerFactory.getLogger(JerseySSEClient.class);
  private final InternalFeatureRepository repository;
  private final FeatureHubConfig config;
  private final EdgeRetryService retryer;
  private final StreamingConnectionTracker<EventInput> connections;
  // each connection is made and read on its own thread from this shared pool, rather than the retryer's (which sleeps
  // while backing off) or a single thread per client (where a new context's connection would queue behind the old
  // context's blocked read). Daemon threads, so a client that is never closed doesn't keep the application running.
  private static final ExecutorService connector = makePool("featurehub-sse-connect");
  // the latest connection's task, so closing can interrupt its read
  @Nullable private volatile Future<?> connectTask;
  private final Client client;
  private final WebTarget target;
  // see workingStreamMs()
  private final long workingStreamMs;
  // closing a connection can block for up to the read timeout (see closeInBackground), so it is done by a shared pool
  // of daemon threads. Closes must not queue behind each other, or old connections stay open long after being replaced.
  private static final ExecutorService closer = makePool("featurehub-sse-close");
  private Consumer<EventInput> notify;

  public JerseySSEClient(@Nullable InternalFeatureRepository repository, @NotNull FeatureHubConfig config,
                         @NotNull EdgeRetryService retryer) {
    this.repository = repository == null ? config.getInternalRepository() : repository;
    this.config = config;
    this.retryer = retryer;
    this.connections = new StreamingConnectionTracker<>(this.repository, config, retryer);
    this.workingStreamMs = StreamingConnectionTracker.workingStreamMs(retryer);

    client = ClientBuilder.newBuilder()
      .register(JacksonFeature.class)
      .register(SseFeature.class).build();

    client.property(ClientProperties.CONNECT_TIMEOUT, retryer.getServerConnectTimeoutMs());
    client.property(ClientProperties.READ_TIMEOUT, retryer.getServerReadTimeoutMs());

    log.trace("client set connect timeout to {} and read timeout to {}", retryer.getServerConnectTimeoutMs(), retryer.getServerReadTimeoutMs());

    target = makeEventSourceTarget(client, config.getRealtimeUrl());
  }

  @NotNull
  protected WebTarget makeEventSourceTarget(Client client, String sdkUrl) {
    return client.target(sdkUrl);
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, @Nullable String contextSha) {
    final CompletableFuture<Readiness> change = new CompletableFuture<>();
    final StreamingConnectionTracker.ContextChange<EventInput> todo = connections.contextChange(newHeader, change);

    // its read may be blocked, so don't wait for it to close
    closeInBackground(todo.getPrevious());
    connect(todo.getConnection());

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
    closeInBackground(connections.close());

    // interrupt any read in progress
    final Future<?> task = connectTask;

    if (task != null) {
      task.cancel(true);
    }

    // always stop the retryer, even if we are not currently connected, otherwise a pending reconnect will
    // connect again after we have been closed
    retryer.close();

    // no features are coming, so don't leave anyone waiting
    connections.notifyAllWaitingClients();

    // like the connection, closing the client can block while it is being read, so do it in the background
    closer.submit(() -> {
      try {
        client.close();
      } catch (Exception e) {
        log.trace("[featurehub-sdk] failed to close client", e);
      }
    });
  }

  /**
   * Closing a connection while another thread is blocked reading it blocks until that read returns (up to the read
   * timeout), so callers other than the connection's own thread close it in the background.
   */
  private static void closeInBackground(@Nullable EventInput source) {
    if (source == null || source.isClosed()) {
      return;
    }

    closer.submit(() -> closeQuietly(source));
  }

  private static ExecutorService makePool(String name) {
    // threads only exist while they are in use, and go once they have been idle for a while
    return Executors.newCachedThreadPool(r -> {
      final Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    });
  }

  private static void closeQuietly(@Nullable EventInput source) {
    try {
      if (source != null && !source.isClosed()) {
        source.close();
      }
    } catch (Exception e) {
      log.trace("[featurehub-sdk] failed to close connection", e);
    }
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  /**
   * Makes the connection.
   *
   * @param header - the x-featurehub header (context) to connect with, if any
   * @param lastEventId - the last event id received for this context, if any
   */
  protected EventInput makeEventSource(@Nullable String header, @Nullable String lastEventId) {
    Invocation.Builder request = target.request();

    if (header != null) {
      request = request.header("x-featurehub", header);
    }

    if (lastEventId != null) {
      request = request.header("Last-Event-ID", lastEventId);
    }

    request = request.header("X-SDK", SdkVersion.sdkVersionHeader("Java-Jersey3"));

    log.trace("[featurehub-sdk] connecting to {}", config.getRealtimeUrl());

    return request.get(EventInput.class);
  }

  /**
   * Makes the connection we have claimed (if any) on its own thread.
   */
  private void connect(@Nullable StreamingConnectionTracker.Connection<EventInput> connection) {
    if (connection == null) {
      return;
    }

    try {
      connectTask = connectExecutor().submit(() -> initEventSource(connection));
    } catch (RejectedExecutionException e) {
      // the executor has been shut down, we can never connect
      connections.abandon(connection);
    }
  }

  private void initEventSource(StreamingConnectionTracker.Connection<EventInput> connection) {
    final EventInput source;

    try {
      source = makeEventSource(connection.getHeader(), connection.getLastEventId());
    } catch (LinkageError e) {
      log.error("You appear to have the wrong version of Jersey or Jackson in your classpath or a conflicting " +
        "version and FeatureHub cannot start", e);
      // retrying won't fix this
      connections.abandon(connection);
      return;
    } catch (Throwable e) {
      // ignored if we were closed while connecting (which may be why it failed) or the context changed, as that
      // has made its own connection
      if (connections.connectionLost(connection, status(e), workingStreamMs(), this)) {
        log.info("[featurehub-sdk] failed to connect to {}", config.getRealtimeUrl(), e);
      }

      return;
    }

    if (!connections.attach(connection, source)) {
      // closed, or the context changed while we were connecting so this connection is for the wrong context
      closeQuietly(source);
      return;
    }

    connection.opened();
    log.trace("[featurehub-sdk] connected to {}", config.getRealtimeUrl());

    // once replaced (e.g. by a context change) we stop as soon as the read returns
    while (!source.isClosed() && !retryer.isStopped() && connections.isCurrent(connection)) {
      if (notify != null) { // this is for testing
        notify.accept(null);
      }

      final InboundEvent event;
      final String data;

      try {
        event = source.read();

        if (event == null) {
          // read timed out or the server closed the connection, the loop condition decides which
          log.trace("server read returned no event");
          continue;
        }

        data = event.readData();
      } catch (Exception e) {
        // if we are no longer the current connection we were deliberately closed (e.g. by a context change). A stream
        // that was working (e.g. quiet until our read timeout) reconnects straight away, otherwise we back off.
        if (connections.connectionLost(connection, status(e), workingStreamMs(), this)) {
          log.error("failed read", e);
        }

        closeQuietly(source);
        return;
      }

      connections.process(connection, this, event.getName(), event.getId(), data);
    }

    // we are the connection's own thread, so we can close it directly
    closeQuietly(source);

    // if we are still current the server closed the connection on us, or the retryer has stopped (in which case it
    // won't reconnect, and nobody is left waiting)
    connections.connectionLost(connection, 0, workingStreamMs(), this);
  }

  private static int status(Throwable e) {
    final Response response = e instanceof WebApplicationException ? ((WebApplicationException) e).getResponse() : null;
    return response == null ? 0 : response.getStatus();
  }

  /**
   * @return how long a stream must stay open, even sending nothing, to count as having worked. At least a second, and
   * at least the connect timeout.
   */
  protected long workingStreamMs() {
    return workingStreamMs;
  }

  @Override
  public Future<Readiness> poll() {
    connect(connections.claim(false));

    return CompletableFuture.completedFuture(repository.getReadiness());
  }

  /**
   * @return where connections are made and read, overrideable for testing
   */
  protected ExecutorService connectExecutor() {
    return connector;
  }

  @Override
  public long currentInterval() {
    return 0;
  }

  @Override
  public void reconnect() {
    // does nothing if something else (e.g. a context change) has already made a new connection
    connect(connections.claim(true));
  }

  public void setNotify(Consumer<EventInput> notify) {
    this.notify = notify;
  }
}
