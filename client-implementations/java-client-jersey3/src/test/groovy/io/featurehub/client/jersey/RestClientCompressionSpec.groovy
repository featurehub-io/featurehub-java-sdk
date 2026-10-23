package io.featurehub.client.jersey

import com.fasterxml.jackson.databind.ObjectMapper
import io.featurehub.client.FeatureHubConfig
import io.featurehub.client.InternalFeatureRepository
import io.featurehub.client.edge.EdgeRetryService
import io.featurehub.sse.model.FeatureEnvironmentCollection
import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import spock.lang.Specification

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.zip.GZIPOutputStream

class RestClientCompressionSpec extends Specification {
  RestTestHarness harness
  InternalFeatureRepository repo
  FeatureHubConfig config
  EdgeRetryService retryer

  def setup() {
    System.setProperty("jersey.config.test.container.port", (10000 + new Random().nextInt(1000)).toString())
    harness = new RestTestHarness()
    harness.setUp()
    repo = Mock()
    config = Mock()
    retryer = Mock()
    config.apiKeys() >> ["123"]
    config.isServerEvaluation() >> false
    retryer.serverConnectTimeoutMs >> 5000
    retryer.serverReadTimeoutMs >> 5000
  }

  def cleanup() {
    harness?.tearDown()
  }

  static byte[] gzip(String data) {
    def bytes = new ByteArrayOutputStream()
    new GZIPOutputStream(bytes).withCloseable { it.write(data.getBytes("UTF-8")) }
    return bytes.toByteArray()
  }

  def "the rest client asks for gzip and decompresses a gzipped response"() {
    given: "a server that sends back a gzipped feature collection"
      String acceptEncoding = null
      def envId = UUID.randomUUID()
      def json = new ObjectMapper().writeValueAsString([new FeatureEnvironmentCollection().id(envId).features([
        new FeatureState().id(UUID.randomUUID()).key("FEATURE_ONE").l(false).version(1).type(FeatureValueType.BOOLEAN).value(true)])])
      config.baseUrl() >> harness.baseUrl({ String encoding ->
        acceptEncoding = encoding
        return Response.ok(gzip(json), MediaType.APPLICATION_JSON_TYPE).header("Content-Encoding", "gzip").build()
      })
    and: "a rest client using the real jersey client"
      def client = new RestClient(repo, null, config, retryer, 0)
    when: "we poll"
      client.poll().get()
    then: "we asked for gzip"
      acceptEncoding.contains("gzip")
    and: "the decompressed features were passed to the repository"
      1 * repo.updateFeatures({ List<FeatureState> states ->
        states.size() == 1 && states[0].key == "FEATURE_ONE" && states[0].environmentId == envId
      }, "polling")
      0 * repo.notify(_, _)
  }
}
