package io.featurehub.client.jersey;

import io.featurehub.client.FeatureHubConfig;
import io.featurehub.client.InternalFeatureRepository;
import io.featurehub.client.edge.BlockingStreamEdgeService;
import io.featurehub.client.edge.EdgeRetryService;
import io.featurehub.client.utils.SdkVersion;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.sse.EventInput;
import org.glassfish.jersey.media.sse.InboundEvent;
import org.glassfish.jersey.media.sse.SseFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Connects with Jersey's EventInput. The connection handling is all in BlockingStreamEdgeService.
 */
public class JerseySSEClient extends BlockingStreamEdgeService {
  private static final Logger log = LoggerFactory.getLogger(JerseySSEClient.class);
  private final Client client;
  private final WebTarget target;
  private Consumer<EventInput> notify;
  // the context and last event id of the connection this thread is making, for the no-arg makeEventSource()
  private final ThreadLocal<String[]> connecting = new ThreadLocal<>();

  public JerseySSEClient(@Nullable InternalFeatureRepository repository, @NotNull FeatureHubConfig config,
                         @NotNull EdgeRetryService retryer) {
    super(repository, config, retryer);

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

    request = request.header("X-SDK", SdkVersion.sdkVersionHeader("Java-Jersey2"));

    log.trace("[featurehub-sdk] connecting to {}", config.getRealtimeUrl());

    return request.get(EventInput.class);
  }

  /**
   * Makes the connection for the context and last event id being connected with.
   *
   * @deprecated override {@link #makeEventSource(String, String)} instead. This is still called, so existing
   * overrides keep working, and by default calls it.
   */
  @Deprecated
  protected EventInput makeEventSource() {
    final String[] connection = connecting.get();
    return makeEventSource(connection == null ? null : connection[0], connection == null ? null : connection[1]);
  }

  @Override
  protected @NotNull Stream openStream(@Nullable String header, @Nullable String lastEventId) {
    connecting.set(new String[] {header, lastEventId});

    try {
      return new EventInputStream(makeEventSource());
    } finally {
      connecting.remove();
    }
  }

  @Override
  protected int statusOf(@NotNull Throwable failure) {
    final Response response =
      failure instanceof WebApplicationException ? ((WebApplicationException) failure).getResponse() : null;
    return response == null ? 0 : response.getStatus();
  }

  @Override
  protected void beforeRead() {
    if (notify != null) { // this is for testing
      notify.accept(null);
    }
  }

  @Override
  protected void closeLibrary() {
    // like the connection, closing the client can block while it is being read, so do it in the background
    runInBackground(() -> {
      try {
        client.close();
      } catch (Exception e) {
        log.trace("[featurehub-sdk] failed to close client", e);
      }
    });
  }

  public void setNotify(Consumer<EventInput> notify) {
    this.notify = notify;
  }

  private static final class EventInputStream implements Stream {
    private final EventInput input;

    EventInputStream(EventInput input) {
      this.input = input;
    }

    @Override
    public boolean isClosed() {
      return input.isClosed();
    }

    @Override
    public @Nullable Event read() {
      final InboundEvent event = input.read();
      return event == null ? null : new Event(event.getName(), event.getId(), event.readData());
    }

    @Override
    public void close() {
      input.close();
    }
  }
}
