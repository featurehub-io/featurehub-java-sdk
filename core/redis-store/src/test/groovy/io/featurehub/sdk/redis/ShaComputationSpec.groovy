package io.featurehub.sdk.redis

import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import spock.lang.Specification

class ShaComputationSpec extends Specification {

  private static FeatureState fs(UUID id, Long version) {
    return new FeatureState()
        .id(id)
        .key('k')
        .version(version)
        .type(FeatureValueType.BOOLEAN)
        .value(true)
        .l(false)
  }

  def "same features in same order produce the same SHA"() {
    given:
      def id1 = UUID.randomUUID()
      def id2 = UUID.randomUUID()
    expect:
      RedisSessionStore.computeSha([fs(id1, 1L), fs(id2, 2L)]) ==
          RedisSessionStore.computeSha([fs(id1, 1L), fs(id2, 2L)])
  }

  def "SHA is order-independent (sorted by id)"() {
    given:
      def id1 = UUID.fromString('00000000-0000-0000-0000-000000000001')
      def id2 = UUID.fromString('00000000-0000-0000-0000-000000000002')
    expect:
      RedisSessionStore.computeSha([fs(id1, 1L), fs(id2, 2L)]) ==
          RedisSessionStore.computeSha([fs(id2, 2L), fs(id1, 1L)])
  }

  def "different versions produce different SHAs"() {
    given:
      def id1 = UUID.randomUUID()
    expect:
      RedisSessionStore.computeSha([fs(id1, 1L)]) !=
          RedisSessionStore.computeSha([fs(id1, 2L)])
  }

  def "different ids produce different SHAs"() {
    given:
      def id1 = UUID.randomUUID()
      def id2 = UUID.randomUUID()
    expect:
      RedisSessionStore.computeSha([fs(id1, 1L)]) !=
          RedisSessionStore.computeSha([fs(id2, 1L)])
  }

  def "0 version is treated as 0"() {
    given:
      def id1 = UUID.randomUUID()
    expect:
      RedisSessionStore.computeSha([fs(id1, 0L)]) ==
          RedisSessionStore.computeSha([fs(id1, 0L)])
  }

  def "empty feature list produces a stable SHA"() {
    expect:
      RedisSessionStore.computeSha([]) == RedisSessionStore.computeSha([])
  }

  def "adding a feature changes the SHA"() {
    given:
      def id1 = UUID.randomUUID()
      def id2 = UUID.randomUUID()
    expect:
      RedisSessionStore.computeSha([fs(id1, 1L)]) !=
          RedisSessionStore.computeSha([fs(id1, 1L), fs(id2, 1L)])
  }
}
