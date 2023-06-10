package io.featurehub.client.jersey;

import cd.connect.openapi.support.ApiClient;
import cd.connect.openapi.support.ApiResponse;
import cd.connect.openapi.support.Pair;
import io.featurehub.sse.model.FeatureEnvironmentCollection;
import io.featurehub.sse.model.FeatureStateUpdate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.ws.rs.core.GenericType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FeatureServiceImpl implements FeatureService {
  private final ApiClient apiClient;

  public FeatureServiceImpl(ApiClient apiClient) {
    this.apiClient = apiClient;
  }

  public @NotNull ApiResponse<List<FeatureEnvironmentCollection>> getFeatureStates(@NotNull List<String> apiKey,
                                                                                   @Nullable String contextSha,
                                                                                   @Nullable Map<String, String> extraHeaders) {
    Object localVarPostBody = new Object();

    // create path and map variables /features/
    String localVarPath = "/features/";

    // query params
    List<Pair> localVarQueryParams = new ArrayList<>();
    Map<String, String> localVarHeaderParams = new HashMap<>();
    Map<String, Object> localVarFormParams = new HashMap<>();

    if (extraHeaders != null) {
      localVarHeaderParams.putAll(extraHeaders);
    }

    localVarQueryParams.addAll(apiClient.parameterToPairs("multi", "apiKey", apiKey));
    localVarQueryParams.addAll(apiClient.parameterToPairs("", "contextSha", contextSha));

    final String[] localVarAccepts = {
      "application/json"
    };
    final String localVarAccept = apiClient.selectHeaderAccept(localVarAccepts);

    final String[] localVarContentTypes = {

    };
    final String localVarContentType = apiClient.selectHeaderContentType(localVarContentTypes);

    String[] localVarAuthNames = new String[] {  };

    GenericType<List<FeatureEnvironmentCollection>> localVarReturnType = new GenericType<List<FeatureEnvironmentCollection>>() {};
    return apiClient.invokeAPI(localVarPath, "GET", localVarQueryParams, localVarPostBody, localVarHeaderParams,
      localVarFormParams, localVarAccept, localVarContentType, localVarAuthNames, localVarReturnType);

  }

  public int setFeatureState(@NotNull String apiKey,
                              @NotNull String featureKey,
                              @NotNull FeatureStateUpdate featureStateUpdate,
                              @Nullable Map<String, String> extraHeaders) {
    // create path and map variables /{apiKey}/{featureKey}
    String localVarPath = String.format("/features/%s/%s", apiKey, featureKey);

    // query params
    Map<String, String> localVarHeaderParams = new HashMap<String, String>();
    Map<String, Object> localVarFormParams = new HashMap<String, Object>();

    if (extraHeaders != null) {
      localVarHeaderParams.putAll(extraHeaders);
    }

    final String[] localVarAccepts = {
      "application/json"
    };
    final String localVarAccept = apiClient.selectHeaderAccept(localVarAccepts);

    final String[] localVarContentTypes = {
      "application/json"
    };
    final String localVarContentType = apiClient.selectHeaderContentType(localVarContentTypes);

    String[] localVarAuthNames = new String[]{};

    GenericType<Object> localVarReturnType = new GenericType<Object>() {};

    return apiClient.invokeAPI(localVarPath, "PUT", null, featureStateUpdate, localVarHeaderParams,
      localVarFormParams, localVarAccept, localVarContentType, localVarAuthNames, localVarReturnType).getStatusCode();
  }
}
