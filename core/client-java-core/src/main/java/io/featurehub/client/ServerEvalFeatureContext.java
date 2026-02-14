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
  private String xHeader;
  private final RepositoryEventHandler newFeatureStateHandler;
  private final RepositoryEventHandler featureUpdatedHandler;
  private final MessageDigest shaDigester;


  public ServerEvalFeatureContext(InternalFeatureRepository repository,
                                  EdgeService edgeService) {
    super(repository, edgeService);

    try {
      shaDigester = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }

    newFeatureStateHandler = repository.registerNewFeatureStateAvailable((fr) -> {
      recordRelativeValuesForUser();
    });

    featureUpdatedHandler = repository.registerFeatureUpdateAvailable((fs) -> {
      recordFeatureChangedForUser((FeatureStateBase<?>)fs);
    });
  }

  @Override
  public void close() {
    super.close();

    newFeatureStateHandler.cancel();
    featureUpdatedHandler.cancel();
  }

  @Override
  public Future<ClientContext> build() {
    String newHeader = FeatureStateUtils.generateXFeatureHubHeaderFromMap(attributes);

    if (newHeader != null || xHeader != null) {
      if ((newHeader != null && xHeader == null) || newHeader == null || !newHeader.equals(xHeader)) {
        xHeader = newHeader;

        repository.repositoryNotReady();
      }
    }

    Future<?> change = edgeService.contextChange(newHeader,
      newHeader == null ? "0" : bytesToHex(shaDigester.digest(newHeader.getBytes(StandardCharsets.UTF_8))));

    xHeader = newHeader;

    CompletableFuture<ClientContext> future = new CompletableFuture<>();

    repository.execute(() -> {
      try {
        change.get();

        future.complete(this);
      } catch (Exception e) {
        log.error("Failed to update", e);
        future.completeExceptionally(e);
      }
    });


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
}
