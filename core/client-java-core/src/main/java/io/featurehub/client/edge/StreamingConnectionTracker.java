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
import java.util.function.LongSupplier;

/**
 * The connection bookkeeping shared by the streaming (SSE) clients: which connection is current, the context it is
 * for, whether callers must wait for features, and when the retryer (rather than us) is going to reconnect.
 *
 * A connection is claimed before it is made, and everything a connection reports (events, closes, failures) is only
 * acted on while it is still the current one. A connection that has been replaced (e.g. by a context change) or closed
 * may still deliver callbacks, and these are ignored.
 *
 * Nothing that calls into listeners or futures is ever done while holding the lock. The retryer is told to reconnect, or
 * to cancel its reconnects, while holding it, as both only queue or cancel work and never call back into us. Doing
 * them in the same step as the retry is marked pending (or cleared) means a cancel can never run between a lost
 * connection being released and its reconnect being queued, which would leave that reconnect to run anyway. For the
 * same reason, everything a connection tells the retryer (e.g. a failure event that stops it) is checked and told in
 * one locked step, so a replaced connection never reaches the retryer. Applying an event to the repository can't be
 * done under the lock, so an event a replaced connection was already applying can still be written.
 *
 * @param <S> - the client library's connection, e.g. an OKHttp EventSource
 */
public class StreamingConnectionTracker<S> {
  private static final Logger log = LoggerFactory.getLogger(StreamingConnectionTracker.class);
  private final Object lock = new Object();
  private final InternalFeatureRepository repository;
  private final FeatureHubConfig config;
  private final EdgeRetryService retryer;
  // see Connection.worked()
  private final LongSupplier workingStreamMs;
  @Nullable private Connection<S> current;
  @Nullable private String header;
  @Nullable private String lastEventId;
  // have we had features (or been told we can't have them) for the current context? Until we have, server evaluated
  // callers must wait. A reconnect doesn't change this, the features we have are still for this context.
  private boolean contextHasFeatures;
  // have the current context's features actually arrived? Only then do we resume with a Last-Event-ID, see
  // claimLocked
  private boolean receivedFeatures;
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
    // has the server sent us our features on this connection?
    private volatile boolean sentFeatures;
    private volatile long openedAt;
    private volatile boolean saidBye;
    // have we told the retryer this connection worked, so it resets its backoff?
    private volatile boolean reportedWorking;

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
     * nothing until it says bye or is disconnected, so staying open long enough counts as working too: features have
     * reached us this way before, and silence just means nothing has changed. A stream that isn't resumed must be sent
     * our features straight away, so until it has, nothing else it sends (e.g. only an ack and a bye) and however long
     * it stays open, it never worked (e.g. a proxy that accepts the connection and passes nothing through).
     *
     * @param workingStreamMs - see {@link StreamingConnectionTracker#workingStreamMs(EdgeRetryService)}
     */
    public boolean worked(long workingStreamMs) {
      if (lastEventId == null) {
        return sentFeatures;
      }

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
    this(repository, config, retryer, () -> workingStreamMs(retryer));
  }

  /**
   * @param workingStreamMs - how long a stream must stay open, even sending nothing, to count as having worked. Asked
   *                        each time a connection is lost.
   */
  public StreamingConnectionTracker(@NotNull InternalFeatureRepository repository, @NotNull FeatureHubConfig config,
                                    @NotNull EdgeRetryService retryer, @NotNull LongSupplier workingStreamMs) {
    this.repository = repository;
    this.config = config;
    this.retryer = retryer;
    this.workingStreamMs = workingStreamMs;
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

    if (retrying && retryer.isReconnectCancelled()) {
      // a context change cancelled this reconnect after it had started. Checked under the lock (as the cancel is made),
      // so it can't claim a connection meant for a later reconnect, once that has backed off.
      return null;
    }

    // only resume if this context's features have arrived. If they haven't, a server that thinks we are up to date
    // sends nothing at all, and callers would wait for features that never come. Being told we can't have them (a
    // failure) doesn't count.
    current = new Connection<>(header, receivedFeatures ? lastEventId : null);
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
   * @return false if the client must drop the connection (closing it and reporting it lost), because applying the
   * event failed in a way that leaves the connection unusable
   */
  public boolean process(@NotNull Connection<S> connection, @NotNull EdgeReconnector reconnector, @Nullable String type,
                         @Nullable String id, @Nullable String data) {
    if (!isCurrent(connection)) {
      log.trace("[featurehub-sdk] ignoring event {} from superseded connection", type);
      return true;
    }

    final SSEResultState state = retryer.fromValue(type);

    if (state == null) { // ignore unknown states
      return true;
    }

    // some libraries (e.g. Jersey) give an event with no data as empty rather than null
    if ((data == null || data.isEmpty()) && (state == SSEResultState.FEATURES || state == SSEResultState.FEATURE ||
      state == SSEResultState.DELETE_FEATURE || state == SSEResultState.CONFIG)) {
      // there is nothing to apply, so it mustn't count as the context's features arriving, nor be resumed from
      log.debug("[featurehub-sdk] ignoring {} event with no data", type);
      return true;
    }

    try {
      log.trace("[featurehub-sdk] decode packet {}:{}", type, data);

      if (state == SSEResultState.FAILURE) {
        // stops the retryer for good, so only if we are still current. Otherwise a context change racing this event
        // would have the old context's failure stop the new context's connection.
        if (!tellRetryer(connection, EdgeConnectionState.API_KEY_NOT_FOUND, reconnector)) {
          return true;
        }

        // a failure need not carry any data, it still has to be recorded
        retryer.convertSSEState(state, data, repository, config.getEnvironmentId());
      } else if (state == SSEResultState.CONFIG) {
        // may stop the retryer (e.g. edge.stale), so like a failure only if we are still current
        synchronized (lock) {
          if (current != connection) {
            return true;
          }

          retryer.edgeConfigInfo(data);
        }
      } else {
        retryer.convertSSEState(state, data, repository, config.getEnvironmentId());
      }

      if (state == SSEResultState.FEATURES) {
        connection.sentFeatures = true;
        // reset the timer
        connection.reportedWorking = true;
        tellRetryer(connection, EdgeConnectionState.SUCCESS, reconnector);
      } else if (state == SSEResultState.BYE) {
        connection.saidBye = true;
      }

      if (state != SSEResultState.ACK && state != SSEResultState.CONFIG) {
        connection.meaningfulEvent = true;
      }

      if (state == SSEResultState.FEATURES || state == SSEResultState.FAILURE) {
        // tell any waiting clients we are now ready. Only the context's features (or being told we can't have them)
        // do, anything else (e.g. a bye before the features) would have them evaluate another context's values. If
        // the connection goes before the features arrive, losing it releases them.
        notifyWaitingClients(connection, state == SSEResultState.FEATURES);
      }
    } catch (Exception e) {
      // those waiting keep waiting, as we still don't have the context's features (releasing them now would have them
      // evaluate another context's values). They are released when the features arrive or the connection goes.
      log.error("[featurehub-sdk] failed to decode packet {}:{}", type, data, e);
      return true;
    } catch (LinkageError e) {
      log.error("[featurehub-sdk] You appear to have the wrong version of a library FeatureHub uses (e.g. Jackson) in " +
        "your classpath or a conflicting version and FeatureHub cannot apply features", e);
      // retrying won't fix this
      abandon(connection);
      return false;
    } catch (Error e) {
      // the client must drop the connection (and report it lost), otherwise it stays current for good, so we never
      // reconnect and those waiting are never released
      log.error("[featurehub-sdk] failed to apply packet {}:{}", type, data, e);
      return false;
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

    return true;
  }

  /**
   * Tells the retryer how the connection is doing, as long as it is still the current one. Done under the lock, so a
   * connection a context change has replaced can never reach the retryer.
   *
   * @return false if the connection had been replaced or closed, so the retryer wasn't told
   */
  private boolean tellRetryer(@NotNull Connection<S> connection, @NotNull EdgeConnectionState state,
                              @NotNull EdgeReconnector reconnector) {
    synchronized (lock) {
      if (current != connection) {
        return false;
      }

      retryer.edgeResult(state, reconnector);
      return true;
    }
  }

  /**
   * The connection has gone, or could not be made. If it is still the current one, tell the retryer, which will
   * reconnect once it has backed off (if the failure may go away), and don't leave anyone waiting on it.
   *
   * @param status - the HTTP status Edge answered with, or 0 if there wasn't one
   * @return false if the connection had already been replaced or closed, so nothing was done
   */
  public boolean connectionLost(@NotNull Connection<S> connection, int status, @NotNull EdgeReconnector reconnector) {
    final List<CompletableFuture<Readiness>> waiting;
    final boolean worked = connection.worked(workingStreamMs.getAsLong());

    synchronized (lock) {
      waiting = releaseLocked(connection, true);

      if (waiting == null) {
        return false;
      }

      try {
        tellRetryerLost(connection, status, worked, reconnector);
      } catch (RuntimeException e) {
        // e.g. the retryer's pool was shut down under it. No reconnect is coming, so a later poll or context change
        // may connect, and those waiting must still be released below.
        log.error("[featurehub-sdk] failed to tell the retryer the connection was lost", e);
        retryPending = false;
      }
    }

    // if the retryer has stopped there won't be a reconnect
    stopped(waiting, retryer.isStopped());

    return true;
  }

  // must hold the lock
  private void tellRetryerLost(@NotNull Connection<S> connection, int status, boolean worked,
                               @NotNull EdgeReconnector reconnector) {
    if (worked && !connection.reportedWorking) {
      // a resumed stream the server is up to date with never sends features, but it worked, so the backoff must
      // reset as it does when features arrive, or after a few failures every later backoff stays at its maximum
      connection.reportedWorking = true;
      retryer.edgeResult(EdgeConnectionState.SUCCESS, reconnector);
    }

    retryer.edgeResult(lostState(connection, status, worked), reconnector);
  }

  private EdgeConnectionState lostState(@NotNull Connection<S> connection, int status, boolean worked) {
    if (EdgeStatus.isApiKeyNotFound(status)) {
      // the api key doesn't exist, retrying won't change that
      return EdgeConnectionState.API_KEY_NOT_FOUND;
    }

    if (EdgeStatus.isNotPermitted(status)) {
      // we aren't allowed in (often a wrong url), retrying won't change that
      return EdgeConnectionState.FAILURE;
    }

    if (!worked) {
      // the stream never worked (we couldn't connect or resolve the host, too many requests, a 5xx, or it accepted us
      // and then dropped the connection), so back off before trying again rather than reconnecting immediately as we
      // do when a working stream drops
      return EdgeConnectionState.CONNECTION_FAILURE;
    }

    return connection.saidBye ? EdgeConnectionState.SERVER_SAID_BYE : EdgeConnectionState.SERVER_WAS_DISCONNECTED;
  }

  /**
   * The connection could not be made because of a problem retrying won't fix (e.g. the wrong version of a library we
   * use, or a malformed url), so we never connect again.
   */
  public void abandon(@NotNull Connection<S> connection) {
    final List<CompletableFuture<Readiness>> waiting;

    synchronized (lock) {
      waiting = releaseLocked(connection, false);

      if (waiting != null) {
        // every later connection would fail the same way, so never connect again
        closed = true;
      }
    }

    if (waiting != null) {
      // nothing will ever reconnect, so don't leave the retryer's thread running
      retryer.close();
      stopped(waiting, true);
    }
  }

  /**
   * The connection can't be made for its context (e.g. the client library won't send the context's header). Retrying
   * won't fix that, so the retryer isn't told, but unlike {@link #abandon} a later context change (or poll) may still
   * connect. Those waiting are released, and if we never had features we have failed.
   */
  public void rejected(@NotNull Connection<S> connection) {
    final List<CompletableFuture<Readiness>> waiting;

    synchronized (lock) {
      waiting = releaseLocked(connection, false);
    }

    if (waiting != null) {
      EdgeStatus.failIfNeverReady(repository, "streaming");
      complete(waiting);
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
    if (forGood) {
      EdgeStatus.failIfNeverReady(repository, "streaming");
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
  private @Nullable List<CompletableFuture<Readiness>> releaseLocked(@NotNull Connection<S> connection, boolean retry) {
    if (current != connection) {
      return null;
    }

    current = null;
    retryPending = retry;

    return takeWaitingClients();
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
        receivedFeatures = false;

        if (current != null) {
          previous = current.source;
          current = null;
        }

        if (retryPending) {
          // the new context needs its features now, rather than once the retryer has backed off. The retryer's
          // reconnect is for the old context, so it must not cut the backoff of a later failure short.
          retryPending = false;
          retryer.cancelReconnects();
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

      // a failure means they don't wait either, but it isn't features we can resume from
      contextHasFeatures = true;

      if (features) {
        receivedFeatures = true;
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
