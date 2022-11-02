package io.featurehub.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class ServerEvalFeatureContext extends BaseClientContext {
  private static final Logger log = LoggerFactory.getLogger(ServerEvalFeatureContext.class);
  private final ObjectSupplier<EdgeService> edgeService;
  private EdgeService currentEdgeService;
  private String xHeader;
  private boolean weOwnEdge;

  private final MessageDigest shaDigester;

  public ServerEvalFeatureContext(FeatureHubConfig config, FeatureRepositoryContext repository,
                                  ObjectSupplier<EdgeService> edgeService) {
    super(repository, config);

    try {
      shaDigester = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }

    this.edgeService = edgeService;
    this.weOwnEdge = false;
  }

  @Override
  public Future<ClientContext> build() {
    String newHeader = FeatureStateUtils.generateXFeatureHubHeaderFromMap(clientContext);

    if (newHeader != null || xHeader != null) {
      if ((newHeader != null && xHeader == null) || newHeader == null || !newHeader.equals(xHeader)) {
        xHeader = newHeader;

        repository.notReady();

        if (currentEdgeService != null && currentEdgeService.isRequiresReplacementOnHeaderChange()) {
          currentEdgeService.close();
          currentEdgeService = edgeService.get();
        }
      }
    }

    if (currentEdgeService == null) {
      currentEdgeService = edgeService.get();
      weOwnEdge = true;
    }

    Future<?> change = currentEdgeService.contextChange(newHeader,
      newHeader == null ? null : bytesToHex(shaDigester.digest(newHeader.getBytes(StandardCharsets.UTF_8))));

    xHeader = newHeader;

    CompletableFuture<ClientContext> future = new CompletableFuture<>();

    try {
      change.get();

      future.complete(this);
    } catch (Exception e) {
      log.error("Failed to update", e);
      future.completeExceptionally(e);
    }


    return future;
  }

  // from https://www.baeldung.com/sha-256-hashing-java as we don't have any libs consistently to do this for us
  private static String bytesToHex(byte[] hash) {
    StringBuilder hexString = new StringBuilder(2 * hash.length);
    for (int i = 0; i < hash.length; i++) {
      String hex = Integer.toHexString(0xff & hash[i]);
      if(hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return hexString.toString();
  }

  @Override
  public EdgeService getEdgeService() {
    return currentEdgeService;
  }

  public ObjectSupplier<EdgeService> getEdgeServiceSupplier() { return edgeService; }

  @Override
  public boolean exists(String key) {
    return false;
  }

  @Override
  public void close() {
    if (weOwnEdge && currentEdgeService != null) {
      currentEdgeService.close();
    }
  }
}
