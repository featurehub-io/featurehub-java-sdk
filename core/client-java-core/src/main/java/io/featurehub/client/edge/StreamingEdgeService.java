package io.featurehub.client.edge;

import io.featurehub.client.EdgeService;
import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * What every streaming (SSE) client shares: the {@link StreamingConnectionTracker} that decides when to connect, and
 * the EdgeService and EdgeReconnector calls that only drive it. The client library specific part is making a
 * connection and closing one.
 *
 * @param <S> - the client library's connection, e.g. an OKHttp EventSource
 */
public abstract class StreamingEdgeService<S> implements EdgeService, EdgeReconnector {
  protected final InternalFeatureRepository repository;
  protected final FeatureHubConfig config;
  protected final EdgeRetryService retryer;
  protected final StreamingConnectionTracker<S> connections;

  protected StreamingEdgeService(@Nullable InternalFeatureRepository repository, @NotNull FeatureHubConfig config,
                                 @NotNull EdgeRetryService retryer) {
    this.repository = repository == null ? config.getInternalRepository() : repository;
    this.config = config;
    this.retryer = retryer;
    this.connections = new StreamingConnectionTracker<>(this.repository, config, retryer, this::workingStreamMs);
  }

  /**
   * Makes the connection we have claimed, if any. Must not block waiting for it to open.
   */
  protected abstract void connect(@Nullable StreamingConnectionTracker.Connection<S> connection);

  /**
   * Closes a connection that is no longer wanted (replaced by a context change, or we are closed). Must not block.
   */
  protected abstract void closeConnection(@NotNull S source);

  /**
   * Called once we are closed and the current connection has been closed, to release anything the client library
   * holds. Must not block.
   */
  protected void closeLibrary() {
  }

  @Override
  public @NotNull Future<Readiness> contextChange(@Nullable String newHeader, @Nullable String contextSha) {
    final CompletableFuture<Readiness> change = new CompletableFuture<>();
    final StreamingConnectionTracker.ContextChange<S> todo = connections.contextChange(newHeader, change);

    if (todo.getPrevious() != null) {
      closeConnection(todo.getPrevious());
    }

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

  /**
   * Applies an event the connection has received.
   *
   * @return false if the retryer has stopped (e.g. a failure event) or we have, so nothing more is coming that we may
   * act on, or the event couldn't be applied in a way that leaves the connection unusable. The client must then close
   * the connection and report it lost straight away, rather than holding it open until the server closes it.
   */
  protected boolean process(@NotNull StreamingConnectionTracker.Connection<S> connection, @Nullable String type,
                            @Nullable String id, @Nullable String data) {
    return connections.process(connection, this, type, id, data) && !isStopped();
  }

  @Override
  public boolean isClientEvaluation() {
    return !config.isServerEvaluation();
  }

  /**
   * @return true if we will never connect again: the retryer has stopped, we were closed, or the connection couldn't
   * be made for a reason retrying won't fix
   */
  @Override
  public boolean isStopped() {
    return retryer.isStopped() || connections.isClosed();
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
    // don't let it try connecting again
    final S source = connections.close();

    // always stop the retryer, even if we are not currently connected, otherwise a pending reconnect will
    // connect again after we have been closed
    retryer.close();

    if (source != null) {
      closeConnection(source);
    }

    closeLibrary();

    // no features are coming, so don't leave anyone waiting
    connections.notifyAllWaitingClients();
  }

  /**
   * @return how long a stream must stay open, even sending nothing, to count as having worked. At least a second, and
   * at least the connect timeout. Asked by the tracker each time a connection is lost.
   */
  protected long workingStreamMs() {
    return StreamingConnectionTracker.workingStreamMs(retryer);
  }
}
