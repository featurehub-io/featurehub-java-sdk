package io.featurehub.client.edge;

import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.Readiness;
import io.featurehub.sse.model.SSEResultState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The connection bookkeeping shared by the streaming (SSE) clients: which connection is current, the context it is
 * for, whether callers must wait for features, and when the retryer (rather than us) is going to reconnect.
 *
 * A connection is claimed before it is made, and everything a connection reports (events, closes, failures) is only
 * acted on while it is still the current one. A connection that has been replaced (e.g. by a context change) or closed
 * may still deliver callbacks, and these are ignored.
 *
 * Nothing that calls into listeners or futures is ever done while holding the lock.
 *
 * @param <S> - the client library's connection, e.g. an OKHttp EventSource
 */
public class StreamingConnectionTracker<S> {
  private static final Logger log = LoggerFactory.getLogger(StreamingConnectionTracker.class);
  private final Object lock = new Object();
  private final InternalFeatureRepository repository;
  private final FeatureHubConfig config;
  private final EdgeRetryService retryer;
  @Nullable private Connection<S> current;
  @Nullable private String header;
  @Nullable private String lastEventId;
  // have we had features (or been told we can't have them) for the current context? Until we have, server evaluated
  // callers must wait. A reconnect doesn't change this, the features we have are still for this context.
  private boolean contextHasFeatures;
  // a connection was lost and the retryer will reconnect once it has backed off, so we must not connect before then
  private boolean retryPending;
  // added to by callers' threads and drained by connection threads
  private final List<CompletableFuture<Readiness>> waitingClients = new ArrayList<>();
  // once closed we never connect again. Set under the lock.
  private volatile boolean closed;

  /**
   * One connection (or attempt at one), with the context it was claimed for.
   */
  public static final class Connection<S> {
    @Nullable private final String header;
    @Nullable private final String lastEventId;
    @Nullable private volatile S source;
    // has the server sent us anything other than an ack or config, and when did the stream open? See worked().
    private volatile boolean meaningfulEvent;
    private volatile long openedAt;
    private volatile boolean saidBye;

    Connection(@Nullable String header, @Nullable String lastEventId) {
      this.header = header;
      this.lastEventId = lastEventId;
    }

    /**
     * @return the x-featurehub header (context) to connect with, if any
     */
    public @Nullable String getHeader() {
      return header;
    }

    /**
     * @return the Last-Event-ID to resume from, if any
     */
    public @Nullable String getLastEventId() {
      return lastEventId;
    }

    /**
     * The stream has opened.
     */
    public void opened() {
      openedAt = System.nanoTime();
    }

    /**
     * Did the stream work? If it did, losing it is normal (e.g. the server's bye, or our read timeout on a quiet
     * stream) and we reconnect straight away. If not, reconnecting straight away would loop as fast as it is dropped,
     * so we back off. A stream that is resumed with a Last-Event-ID the server is up to date with legitimately sends
     * nothing until it says bye or is disconnected, so staying open long enough counts as working too. One that sends
     * nothing (or only an ack or config) and is dropped quickly never worked.
     *
     * @param workingStreamMs - see {@link StreamingConnectionTracker#workingStreamMs(EdgeRetryService)}
     */
    public boolean worked(long workingStreamMs) {
      if (meaningfulEvent) {
        return true;
      }

      final long opened = openedAt;

      return opened != 0 && (System.nanoTime() - opened) / 1_000_000L >= workingStreamMs;
    }
  }

  /**
   * What a context change needs the client to do once the lock has been released.
   */
  public static final class ContextChange<S> {
    @Nullable private final S previous;
    @Nullable private final Connection<S> connection;

    ContextChange(@Nullable S previous, @Nullable Connection<S> connection) {
      this.previous = previous;
      this.connection = connection;
    }

    /**
     * @return the old context's connection, which the client must close
     */
    public @Nullable S getPrevious() {
      return previous;
    }

    /**
     * @return a connection claimed for the new context, which the client must make
     */
    public @Nullable Connection<S> getConnection() {
      return connection;
    }
  }

  public StreamingConnectionTracker(@NotNull InternalFeatureRepository repository, @NotNull FeatureHubConfig config,
                                    @NotNull EdgeRetryService retryer) {
    this.repository = repository;
    this.config = config;
    this.retryer = retryer;
  }

  /**
   * @return how long a stream must stay open, even sending nothing, to count as having worked. At least a second, and
   * at least the connect timeout.
   */
  public static long workingStreamMs(@NotNull EdgeRetryService retryer) {
    return Math.max(1000L, retryer.getServerConnectTimeoutMs());
  }

  /**
   * Claims a new connection unless one is already current (connecting or connected), or the retryer is backing off
   * before reconnecting.
   *
   * @param retrying - true if this is the retryer reconnecting once it has backed off
   * @return the connection the client must now make, or null if it must not
   */
  public @Nullable Connection<S> claim(boolean retrying) {
    synchronized (lock) {
      return claimLocked(retrying);
    }
  }

  private @Nullable Connection<S> claimLocked(boolean retrying) {
    if (closed || retryer.isStopped() || current != null || (retryPending && !retrying)) {
      return null;
    }

    // only resume if we have this context's features. If we don't, a server that thinks we are up to date sends
    // nothing at all, and callers would wait for features that never come.
    current = new Connection<>(header, contextHasFeatures ? lastEventId : null);
    retryPending = false;

    return current;
  }

  /**
   * The client has made the connection.
   *
   * @return false if it has been replaced or we were closed in the meantime, and the client must close it
   */
  public boolean attach(@NotNull Connection<S> connection, @NotNull S source) {
    synchronized (lock) {
      if (current != connection) {
        return false;
      }

      connection.source = source;
      return true;
    }
  }

  public boolean isCurrent(@NotNull Connection<S> connection) {
    synchronized (lock) {
      return current == connection;
    }
  }

  public boolean isClosed() {
    return closed;
  }

  /**
   * Applies an event the connection has received. Ignored if the connection has been replaced, as anything it sends
   * is for the old context.
   *
   * @param reconnector - told to the retryer
   */
  public void process(@NotNull Connection<S> connection, @NotNull EdgeReconnector reconnector, @Nullable String type,
                      @Nullable String id, @Nullable String data) {
    if (!isCurrent(connection)) {
      log.trace("[featurehub-sdk] ignoring event {} from superseded connection", type);
      return;
    }

    final SSEResultState state = retryer.fromValue(type);

    if (state == null) { // ignore unknown states
      return;
    }

    try {
      log.trace("[featurehub-sdk] decode packet {}:{}", type, data);

      if (state == SSEResultState.CONFIG) {
        retryer.edgeConfigInfo(data);
      } else {
        // a failure need not carry any data, it still has to be recorded
        retryer.convertSSEState(state, data, repository, config.getEnvironmentId());
      }

      if (state == SSEResultState.FEATURES) {
        // reset the timer
        retryer.edgeResult(EdgeConnectionState.SUCCESS, reconnector);
      } else if (state == SSEResultState.BYE) {
        connection.saidBye = true;
      } else if (state == SSEResultState.FAILURE) {
        retryer.edgeResult(EdgeConnectionState.API_KEY_NOT_FOUND, reconnector);
      }

      if (state != SSEResultState.ACK && state != SSEResultState.CONFIG) {
        connection.meaningfulEvent = true;
        // tell any waiting clients we are now ready
        notifyWaitingClients(connection,
          state == SSEResultState.FEATURES || state == SSEResultState.FAILURE);
      }
    } catch (Exception e) {
      log.error("[featurehub-sdk] failed to decode packet {}:{}", type, data, e);
      // don't leave anyone waiting on an event we couldn't apply, but we still don't have the features
      notifyWaitingClients(connection, false);
      return;
    }

    // only record the id once the event has been applied, so if we failed to apply it a reconnect has the server send
    // it again
    if (id != null) {
      synchronized (lock) {
        if (current == connection) {
          lastEventId = id;
        }
      }
    }
  }

  /**
   * The connection has gone, or could not be made. If it is still the current one, tell the retryer, which will
   * reconnect once it has backed off (if the failure may go away), and don't leave anyone waiting on it.
   *
   * @param status - the HTTP status Edge answered with, or 0 if there wasn't one
   * @param workingStreamMs - see {@link Connection#worked(long)}
   * @return false if the connection had already been replaced or closed, so nothing was done
   */
  public boolean connectionLost(@NotNull Connection<S> connection, int status, long workingStreamMs,
                                @NotNull EdgeReconnector reconnector) {
    final List<CompletableFuture<Readiness>> waiting = release(connection, true);

    if (waiting == null) {
      return false;
    }

    final EdgeConnectionState state;

    if (EdgeStatus.requestsArePointless(status)) {
      // the api key doesn't exist, retrying won't change that
      state = EdgeConnectionState.API_KEY_NOT_FOUND;
    } else if (!connection.worked(workingStreamMs)) {
      // the stream never worked (we couldn't connect or resolve the host, we were refused, too many requests, a 5xx,
      // or it accepted us and then dropped the connection), so back off before trying again rather than reconnecting
      // immediately as we do when a working stream drops
      state = EdgeConnectionState.CONNECTION_FAILURE;
    } else if (connection.saidBye) {
      state = EdgeConnectionState.SERVER_SAID_BYE;
    } else {
      state = EdgeConnectionState.SERVER_WAS_DISCONNECTED;
    }

    retryer.edgeResult(state, reconnector);

    // if the retryer has stopped there won't be a reconnect
    stopped(waiting, retryer.isStopped());

    return true;
  }

  /**
   * The connection could not be made because of a problem with the libraries we are using, which retrying won't fix.
   */
  public void abandon(@NotNull Connection<S> connection) {
    final List<CompletableFuture<Readiness>> waiting = release(connection, false);

    if (waiting != null) {
      stopped(waiting, true);
    }
  }

  /**
   * Releases those who were waiting on a connection that has gone. Anyone who started waiting after it went waits for
   * the reconnect, unless no features are coming at all, in which case nobody must be left waiting, and if we never
   * had any features we have failed.
   *
   * @param forGood - true if no features are coming
   */
  private void stopped(@NotNull List<CompletableFuture<Readiness>> waiting, boolean forGood) {
    // before anyone is told, so they are told we have failed
    if (forGood && repository.getReadiness() == Readiness.NotReady) {
      repository.notify(SSEResultState.FAILURE);
    }

    complete(waiting);

    if (forGood) {
      notifyAllWaitingClients();
    }
  }

  /**
   * Releases the connection if it is still the current one. Those waiting on it are taken in the same step, so anyone
   * who starts waiting after this waits for the replacement connection.
   *
   * @param retry - true if the caller is telling the retryer, so until it reconnects nothing else may
   * @return those waiting on the connection if it was the current one, otherwise null
   */
  private @Nullable List<CompletableFuture<Readiness>> release(@NotNull Connection<S> connection, boolean retry) {
    synchronized (lock) {
      if (current != connection) {
        return null;
      }

      current = null;
      retryPending = retry;

      return takeWaitingClients();
    }
  }

  /**
   * A context has been built. For client evaluated keys nothing about the connection changes and nobody waits, we just
   * make sure we are connecting. For server evaluated keys a changed context replaces the connection, and callers wait
   * until we have the context's features.
   *
   * @param change - completed once the caller may carry on
   */
  public @NotNull ContextChange<S> contextChange(@Nullable String newHeader,
                                                 @NotNull CompletableFuture<Readiness> change) {
    final boolean serverEvaluated = config.isServerEvaluation();
    S previous = null;
    boolean contextChanged = false;
    boolean cancelReconnects = false;
    final Connection<S> connection;
    final boolean waiting;

    // done in one step, so a reconnect can't claim the connection between us dropping the old context's connection
    // and deciding whether to wait for the new one (and so tell the caller we are ready before any of its features
    // have arrived)
    synchronized (lock) {
      if (serverEvaluated && !closed && !retryer.isStopped() && !Objects.equals(newHeader, header)) {
        contextChanged = true;
        header = newHeader;
        // the context has changed, so the server's event stream is no longer the one we were following
        lastEventId = null;
        contextHasFeatures = false;

        if (current != null) {
          previous = current.source;
          current = null;
        }

        if (retryPending) {
          // the new context needs its features now, rather than once the retryer has backed off. The retryer's
          // reconnect is for the old context, so it must not cut the backoff of a later failure short.
          retryPending = false;
          cancelReconnects = true;
        }
      }

      // while the retryer is backing off we leave the reconnect to it, connecting now would ignore the backoff
      connection = claimLocked(false);
      // until we have features for this context, a connection (current or just claimed) will tell us when we have.
      // Once we have them, a reconnect doesn't make them any less valid. While backing off no features are coming.
      waiting = serverEvaluated && !contextHasFeatures && current != null;

      if (waiting) {
        waitingClients.add(change);
      }
    }

    if (contextChanged) {
      log.warn("[featurehub-sdk] please only use server evaluated keys with SSE with one repository per SSE client.");
    }

    if (cancelReconnects) {
      retryer.cancelReconnects();
    }

    if (!waiting) {
      change.complete(repository.getReadiness());
    }

    return new ContextChange<>(previous, connection);
  }

  /**
   * We will never connect again.
   *
   * @return the current connection, which the client must close
   */
  public @Nullable S close() {
    synchronized (lock) {
      closed = true;
      final S source = current == null ? null : current.source;
      current = null;
      return source;
    }
  }

  /**
   * Tells the waiting clients we are ready, as long as the connection is still current. If a context change has
   * replaced it, those waiting are waiting for the new context's features, not the ones the old connection just sent.
   *
   * @param features - true if we now have the context's features, so later callers needn't wait for them
   */
  private void notifyWaitingClients(@NotNull Connection<S> connection, boolean features) {
    final List<CompletableFuture<Readiness>> waiting;

    synchronized (lock) {
      if (current != connection) {
        return;
      }

      if (features) {
        contextHasFeatures = true;
      }

      waiting = takeWaitingClients();
    }

    complete(waiting);
  }

  /**
   * Tells all waiting clients where we are, used when no features are coming.
   */
  public void notifyAllWaitingClients() {
    final List<CompletableFuture<Readiness>> waiting;

    synchronized (lock) {
      waiting = takeWaitingClients();
    }

    complete(waiting);
  }

  private List<CompletableFuture<Readiness>> takeWaitingClients() {
    final List<CompletableFuture<Readiness>> waiting = new ArrayList<>(waitingClients);
    waitingClients.clear();
    return waiting;
  }

  // done outside the lock, as completing a future runs its callbacks
  private void complete(List<CompletableFuture<Readiness>> waiting) {
    for (CompletableFuture<Readiness> future : waiting) {
      future.complete(repository.getReadiness());
    }
  }
}
