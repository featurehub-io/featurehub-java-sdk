package io.featurehub.client.edge;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * An SSE client for libraries whose connections are read by blocking until each event arrives (e.g. Jersey's
 * EventInput). Everything apart from opening a connection is done here, so the library specific client only has to
 * open a {@link Stream} and say what HTTP status a failure carries.
 *
 * Each connection is made and read on its own thread from a shared pool, rather than the retryer's (which sleeps while
 * backing off) or a single thread per client (where a new context's connection would queue behind the old context's
 * blocked read).
 */
public abstract class BlockingStreamEdgeService implements EdgeService, EdgeReconnector {
  private static final Logger log = LoggerFactory.getLogger(BlockingStreamEdgeService.class);
  // daemon threads, so a client that is never closed doesn't keep the application running
  private static final ExecutorService connector = makePool("featurehub-sse-connect");
  // closing a connection can block for up to the read timeout (see closeInBackground), so it is done by a shared pool
  // of daemon threads. Closes must not queue behind each other, or old connections stay open long after being replaced.
  private static final ExecutorService closer = makePool("featurehub-sse-close");
  protected final InternalFeatureRepository repository;
  protected final FeatureHubConfig config;
  protected final EdgeRetryService retryer;
  private final StreamingConnectionTracker<Stream> connections;
  // the tasks making or reading our connections (including ones that have been replaced but are still finishing), so
  // closing can interrupt them all
  private final Set<Future<?>> connectTasks = ConcurrentHashMap.newKeySet();
  // see workingStreamMs()
  private final long workingStreamMs;

  /**
   * A connection made by the client library.
   */
  public interface Stream {
    boolean isClosed();

    /**
     * Blocks until the next event arrives.
     *
     * @return the event, or null if the read timed out or the server closed the connection (isClosed() says which)
     */
    @Nullable Event read() throws Exception;

    /**
     * May block while another thread is reading, see closeInBackground.
     */
    void close() throws Exception;
  }

  /**
   * An event received on a stream.
   */
  public static final class Event {
    @Nullable private final String name;
    @Nullable private final String id;
    @Nullable private final String data;

    public Event(@Nullable String name, @Nullable String id, @Nullable String data) {
      this.name = name;
      this.id = id;
      this.data = data;
    }

    public @Nullable String getName() {
      return name;
    }

    public @Nullable String getId() {
      return id;
    }

    public @Nullable String getData() {
      return data;
    }
  }

  protected BlockingStreamEdgeService(@Nullable InternalFeatureRepository repository, @NotNull FeatureHubConfig config,
                                      @NotNull EdgeRetryService retryer) {
    this.repository = repository == null ? config.getInternalRepository() : repository;
    this.config = config;
    this.retryer = retryer;
    this.connections = new StreamingConnectionTracker<>(this.repository, config, retryer);
    this.workingStreamMs = StreamingConnectionTracker.workingStreamMs(retryer);
  }

  /**
   * Opens the connection.
   *
   * @param header - the x-featurehub header (context) to connect with, if any
   * @param lastEventId - the last event id received for this context, if any
   */
  protected abstract @NotNull Stream openStream(@Nullable String header, @Nullable String lastEventId) throws Exception;

  /**
   * @return the HTTP status Edge answered with that caused this failure, or 0 if there wasn't one
   */
  protected abstract int statusOf(@NotNull Throwable failure);

  /**
   * Called once we are closed, to release anything the client library holds. Must not block, see runInBackground.
   */
  protected void closeLibrary() {
  }

  /**
   * Called before each read, for testing.
   */
  protected void beforeRead() {
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, @Nullable String contextSha) {
    final CompletableFuture<Readiness> change = new CompletableFuture<>();
    final StreamingConnectionTracker.ContextChange<Stream> todo = connections.contextChange(newHeader, change);

    // its read may be blocked, so don't wait for it to close
    closeInBackground(todo.getPrevious());
    connect(todo.getConnection());

    return change;
  }

  @Override
  public Future<Readiness> poll() {
    connect(connections.claim(false));

    return CompletableFuture.completedFuture(repository.getReadiness());
  }

  @Override
  public void reconnect() {
    // does nothing if something else (e.g. a context change) has already made a new connection
    connect(connections.claim(true));
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
  public long currentInterval() {
    return 0;
  }

  @Override
  public @NotNull FeatureHubConfig getConfig() {
    return config;
  }

  @Override
  public void close() {
    closeInBackground(connections.close());

    // interrupt any connect or read in progress, including those of connections that have been replaced
    for (Future<?> task : connectTasks) {
      task.cancel(true);
    }

    connectTasks.clear();

    // always stop the retryer, even if we are not currently connected, otherwise a pending reconnect will
    // connect again after we have been closed
    retryer.close();

    // no features are coming, so don't leave anyone waiting
    connections.notifyAllWaitingClients();

    closeLibrary();
  }

  /**
   * @return where connections are made and read, overrideable for testing
   */
  protected ExecutorService connectExecutor() {
    return connector;
  }

  /**
   * @return how long a stream must stay open, even sending nothing, to count as having worked. At least a second, and
   * at least the connect timeout.
   */
  protected long workingStreamMs() {
    return workingStreamMs;
  }

  /**
   * Runs something that may block (e.g. closing a connection that is being read) on the shared pool of daemon threads.
   */
  protected static void runInBackground(@NotNull Runnable task) {
    closer.submit(task);
  }

  /**
   * Closing a connection while another thread is blocked reading it blocks until that read returns (up to the read
   * timeout), so callers other than the connection's own thread close it in the background.
   */
  public static void closeInBackground(@Nullable Stream source) {
    if (source == null || source.isClosed()) {
      return;
    }

    runInBackground(() -> closeQuietly(source));
  }

  private static ExecutorService makePool(String name) {
    // threads only exist while they are in use, and go once they have been idle for a while
    return Executors.newCachedThreadPool(r -> {
      final Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    });
  }

  private static void closeQuietly(@Nullable Stream source) {
    try {
      if (source != null && !source.isClosed()) {
        source.close();
      }
    } catch (Exception e) {
      log.trace("[featurehub-sdk] failed to close connection", e);
    }
  }

  /**
   * Makes the connection we have claimed (if any) on its own thread.
   */
  private void connect(@Nullable StreamingConnectionTracker.Connection<Stream> connection) {
    if (connection == null) {
      return;
    }

    // forget the tasks that have finished
    connectTasks.removeIf(Future::isDone);

    try {
      final Future<?> task = connectExecutor().submit(() -> run(connection));

      if (task != null) {
        connectTasks.add(task);
      }
    } catch (RejectedExecutionException e) {
      // the executor has been shut down, we can never connect
      connections.abandon(connection);
    }
  }

  private void run(StreamingConnectionTracker.Connection<Stream> connection) {
    final Stream source;

    try {
      source = openStream(connection.getHeader(), connection.getLastEventId());
    } catch (LinkageError e) {
      log.error("You appear to have the wrong version of a library FeatureHub uses (e.g. Jersey or Jackson) in your " +
        "classpath or a conflicting version and FeatureHub cannot start", e);
      // retrying won't fix this
      connections.abandon(connection);
      return;
    } catch (Throwable e) {
      // ignored if we were closed while connecting (which may be why it failed) or the context changed, as that
      // has made its own connection
      if (connections.connectionLost(connection, statusOf(e), workingStreamMs(), this)) {
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
      beforeRead();

      final Event event;

      try {
        event = source.read();

        if (event == null) {
          // read timed out or the server closed the connection, the loop condition decides which
          log.trace("server read returned no event");
          continue;
        }
      } catch (Exception e) {
        // if we are no longer the current connection we were deliberately closed (e.g. by a context change). A stream
        // that was working (e.g. quiet until our read timeout) reconnects straight away, otherwise we back off.
        if (connections.connectionLost(connection, statusOf(e), workingStreamMs(), this)) {
          log.error("failed read", e);
        }

        closeQuietly(source);
        return;
      }

      connections.process(connection, this, event.getName(), event.getId(), event.getData());
    }

    // we are the connection's own thread, so we can close it directly
    closeQuietly(source);

    // if we are still current the server closed the connection on us, or the retryer has stopped (in which case it
    // won't reconnect, and nobody is left waiting)
    connections.connectionLost(connection, 0, workingStreamMs(), this);
  }
}
