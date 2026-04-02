package io.featurehub.client

import io.featurehub.sse.model.FeatureState
import io.featurehub.sse.model.FeatureValueType
import spock.lang.Specification

enum DvFeat implements Feature { flag, str, num, json, missing }

class DefaultValueSpec extends Specification {
  UUID envId = UUID.randomUUID()
  ClientFeatureRepository repo
  BaseClientContext ctx

  def setup() {
    repo = new ClientFeatureRepository(1)
    repo.updateFeatures([
      new FeatureState().id(UUID.randomUUID()).environmentId(envId).version(1).key('flag').value(true).type(FeatureValueType.BOOLEAN),
      new FeatureState().id(UUID.randomUUID()).environmentId(envId).version(1).key('str').value('hello').type(FeatureValueType.STRING),
      new FeatureState().id(UUID.randomUUID()).environmentId(envId).version(1).key('num').value(42).type(FeatureValueType.NUMBER),
      new FeatureState().id(UUID.randomUUID()).environmentId(envId).version(1).key('json').value('{"x":1}').type(FeatureValueType.JSON),
    ])
    ctx = new BaseClientContext(repo, Mock(EdgeService))
  }

  def "getString returns the feature value when set, and the default when missing"() {
    expect:
      repo.getFeat('str').getString('default') == 'hello'
      repo.getFeat('missing').getString('default') == 'default'
      repo.getFeat('missing').getString(null) == null
  }

  def "getFlag returns the feature value when set, and the default when missing"() {
    expect:
      repo.getFeat('flag').getFlag(false)
      !repo.getFeat('missing').getFlag(false)
      repo.getFeat('missing').getFlag(true)
  }

  def "isEnabled returns the feature value when set, and the default when missing"() {
    expect:
      repo.getFeat('flag').isEnabled(false)
      !repo.getFeat('missing').isEnabled(false)
      repo.getFeat('missing').isEnabled(true)
  }

  def "getNumber returns the feature value when set, and the default when missing"() {
    expect:
      repo.getFeat('num').getNumber(BigDecimal.ZERO) == 42
      repo.getFeat('missing').getNumber(BigDecimal.ZERO) == BigDecimal.ZERO
      repo.getFeat('missing').getNumber(null) == null
  }

  def "getRawJson returns the feature value when set, and the default when missing"() {
    expect:
      repo.getFeat('json').getRawJson('{}') == '{"x":1}'
      repo.getFeat('missing').getRawJson('{}') == '{}'
      repo.getFeat('missing').getRawJson(null) == null
  }

  def "getValue returns the feature value when set, and the default when missing"() {
    expect:
      repo.getFeat('str', String).getValue(String, 'default') == 'hello'
      repo.getFeat('missing', String).getValue(String, 'default') == 'default'
      repo.getFeat('missing', String).getValue(String, null) == null
  }

  def "ClientContext getString delegates correctly for String and Feature name variants"() {
    expect:
      ctx.getString('str', 'default') == 'hello'
      ctx.getString('missing', 'default') == 'default'
      ctx.getString(DvFeat.str, 'default') == 'hello'
      ctx.getString(DvFeat.missing, 'default') == 'default'
  }

  def "ClientContext getFlag delegates correctly for String and Feature name variants"() {
    expect:
      ctx.getFlag('flag', false)
      !ctx.getFlag('missing', false)
      ctx.getFlag(DvFeat.flag, false)
      !ctx.getFlag(DvFeat.missing, false)
  }

  def "ClientContext isEnabled delegates correctly for String and Feature name variants"() {
    expect:
      ctx.isEnabled('flag', false)
      !ctx.isEnabled('missing', false)
      ctx.isEnabled(DvFeat.flag, false)
      !ctx.isEnabled(DvFeat.missing, false)
  }

  def "ClientContext getNumber delegates correctly for String and Feature name variants"() {
    expect:
      ctx.getNumber('num', BigDecimal.ZERO) == 42
      ctx.getNumber('missing', BigDecimal.ZERO) == BigDecimal.ZERO
      ctx.getNumber(DvFeat.num, BigDecimal.ZERO) == 42
      ctx.getNumber(DvFeat.missing, BigDecimal.ZERO) == BigDecimal.ZERO
  }

  def "ClientContext getRawJson delegates correctly for String and Feature name variants"() {
    expect:
      ctx.getRawJson('json', '{}') == '{"x":1}'
      ctx.getRawJson('missing', '{}') == '{}'
      ctx.getRawJson(DvFeat.json, '{}') == '{"x":1}'
      ctx.getRawJson(DvFeat.missing, '{}') == '{}'
  }

  def "ClientContext getValue delegates correctly for String and Feature name variants"() {
    expect:
      ctx.getValue('str', String, 'default') == 'hello'
      ctx.getValue('missing', String, 'default') == 'default'
      ctx.getValue(DvFeat.str, String, 'default') == 'hello'
      ctx.getValue(DvFeat.missing, String, 'default') == 'default'
  }
}