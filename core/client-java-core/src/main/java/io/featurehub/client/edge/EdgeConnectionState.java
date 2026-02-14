package io.featurehub.client.edge;

public enum EdgeConnectionState {
  // {SSE + GET]
  // the api key was not known by the server, and this is a terminal failure. We cannot recover from this
  // so we need to set the repository into FAILURE mode
  API_KEY_NOT_FOUND,

  // [SSE + GET]
  // we timed out trying to read the server. We should backoff briefly and try and connect again. May
  // require increasing backoff
  SERVER_READ_TIMEOUT, // timeout connecting to url, retryable

  // [SSE Only] this is the normal ping/pong of the server connection disconnecting us, we should delay a random amount
  // of time an reconnect.
  SERVER_SAID_BYE, // we got kicked off after a normal timeout using eventsource

  // [SSE + GET] we never received a response after we did actually connect, we should backoff
  SERVER_WAS_DISCONNECTED, // we got a disconnect before we received a "bye"

  CONNECTION_FAILURE, // e.g. java.net.ConnectionException - such as the host not existing or not being able to be connected to at all

  SUCCESS,
  // total failure (e.g. 403 or 401 coming from Edge, so stop trying)
  FAILURE,

}
