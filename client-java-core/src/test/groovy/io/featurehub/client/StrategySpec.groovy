package io.featurehub.client

import io.featurehub.sse.model.FeatureValueType
import io.featurehub.sse.model.RolloutStrategyAttributeConditional
import io.featurehub.sse.model.RolloutStrategyFieldType
import io.featurehub.sse.model.StrategyAttributeCountryName
import io.featurehub.sse.model.StrategyAttributePlatformName
import io.featurehub.sse.model.StrategyAttributeWellKnownNames
import io.featurehub.sse.model.FeatureRolloutStrategy
import io.featurehub.sse.model.FeatureRolloutStrategyAttribute
import io.featurehub.sse.model.FeatureState
import spock.lang.Specification

import java.util.concurrent.ExecutorService

class StrategySpec extends Specification {
  ClientFeatureRepository repo
  EdgeService edge

  def setup() {
    def exec = [
      execute: { Runnable cmd -> cmd.run() },
      shutdownNow: { -> },
      isShutdown: { false }
    ] as ExecutorService

    repo = new ClientFeatureRepository(exec)

    edge = Mock(EdgeService)
  }

  def "basic boolean strategy"() {
    given: "i have a basic boolean feature with a rollout strategy"
        def f = new FeatureState()
          .key("bool1")
          .value(true)
          .id(UUID.randomUUID())
          .version(1)
          .type(FeatureValueType.BOOLEAN)
          .strategies([new FeatureRolloutStrategy().value(false).attributes(
            [new FeatureRolloutStrategyAttribute().type(RolloutStrategyFieldType.STRING)
               .conditional(RolloutStrategyAttributeConditional.EQUALS)
               .fieldName(StrategyAttributeWellKnownNames.COUNTRY.getValue())
               .values([StrategyAttributeCountryName.TURKEY.getValue()])
            ]
          )])
    and: "we have a feature repository with this in it"
        repo.updateFeatures([f])
    when: "we create a client context matching the strategy"
        def cc = new TestContext(repo, edge).country(StrategyAttributeCountryName.TURKEY)
    and: "we create a context not matching the strategy"
        def ccNot = new TestContext(repo, edge).country(StrategyAttributeCountryName.NEW_ZEALAND)
    then: "without the context it is true"
        repo.getFeat("bool1").flag
    and: "with the good context it is false"
        !cc.feature("bool1").flag
        !cc.isEnabled("bool1")
    and: "with the bad context it is true"
        ccNot.feature("bool1").flag
  }

  def "number strategy"() {
    given: "i have a basic number feature with a rollout strategy"
        def f = new FeatureState()
          .key("num1")
          .id(UUID.randomUUID())
          .value(16)
          .version(1)
          .type(FeatureValueType.NUMBER)
          .strategies([new FeatureRolloutStrategy().value(6).attributes(
            [new FeatureRolloutStrategyAttribute().type(RolloutStrategyFieldType.NUMBER)
               .conditional(RolloutStrategyAttributeConditional.GREATER_EQUALS)
               .fieldName("age")
               .values([40])
            ]
          )
                       , new FeatureRolloutStrategy().value(10).attributes(
            [new FeatureRolloutStrategyAttribute().type(RolloutStrategyFieldType.NUMBER)
               .conditional(RolloutStrategyAttributeConditional.GREATER_EQUALS)
               .fieldName("age")
               .values([20])
            ]
          )
          ])
    and: "we have a feature repository with this in it"
        repo.updateFeatures([f])
    when: "we create a client context matching the strategy"
        def ccFirst = new TestContext(repo, edge).attr("age", "27")
        def ccNoMatch = new TestContext(repo, edge).attr("age", "18")
        def ccSecond = new TestContext(repo, edge).attr("age", "43")
    then: "without the context it is true"
        repo.getFeat("num1").number == 16
        ccNoMatch.feature("num1").number == 16
        ccSecond.feature("num1").number == 6
        ccFirst.feature("num1").number == 10
  }

  def "string strategy"() {
    given: "i have a basic string feature with a rollout strategy"
        def f = new FeatureState()
          .key("feat1")
          .value("feature")
          .id(UUID.randomUUID())
          .version(1)
          .type(FeatureValueType.STRING)
          .strategies([new FeatureRolloutStrategy().value("not-mobile").attributes(
            [new FeatureRolloutStrategyAttribute().type(RolloutStrategyFieldType.STRING)
               .conditional(RolloutStrategyAttributeConditional.EXCLUDES)
               .fieldName(StrategyAttributeWellKnownNames.PLATFORM.getValue())
               .values([StrategyAttributePlatformName.ANDROID.value, StrategyAttributePlatformName.IOS.value])
            ]
          )
                       , new FeatureRolloutStrategy().value("older-than-twenty").attributes(
            [new FeatureRolloutStrategyAttribute().type(RolloutStrategyFieldType.NUMBER)
               .conditional(RolloutStrategyAttributeConditional.GREATER_EQUALS)
               .fieldName("age")
               .values([20])
            ]
          )
          ])
    and: "we have a feature repository with this in it"
        repo.updateFeatures([f])
    when: "we create a client context matching the strategy"
        def ccFirst = new TestContext(repo, edge).attr("age", "27").platform(StrategyAttributePlatformName.IOS)
        def ccNoMatch = new TestContext(repo, edge).attr("age", "18").platform(StrategyAttributePlatformName.ANDROID)
        def ccSecond = new TestContext(repo, edge).attr("age", "43").platform(StrategyAttributePlatformName.MACOS)
        def ccThird = new TestContext(repo, edge).attr("age", "18").platform(StrategyAttributePlatformName.MACOS)
        def ccEmpty = new TestContext(repo, edge)
    then: "without the context it is true"
        repo.getFeat("feat1").string == "feature"
        ccNoMatch.feature("feat1").string == "feature"
        ccSecond.feature("feat1").string == "not-mobile"
        ccFirst.feature("feat1").string == "older-than-twenty"
        ccThird.feature("feat1").string == "not-mobile"
        ccEmpty.feature("feat1").string == "feature"
  }

  def "json strategy"() {
    given: "i have a basic json feature with a rollout strategy"
        def f = new FeatureState()
          .key("feat1")
          .id(UUID.randomUUID())
          .value("feature")
          .version(1)
          .type(FeatureValueType.JSON)
          .strategies([new FeatureRolloutStrategy().value("not-mobile").attributes(
            [new FeatureRolloutStrategyAttribute().type(RolloutStrategyFieldType.STRING)
               .conditional(RolloutStrategyAttributeConditional.EXCLUDES)
               .fieldName(StrategyAttributeWellKnownNames.PLATFORM.getValue())
               .values([StrategyAttributePlatformName.ANDROID.value, StrategyAttributePlatformName.IOS.value])
            ]
          ), new FeatureRolloutStrategy().value("older-than-twenty").attributes(
            [new FeatureRolloutStrategyAttribute().type(RolloutStrategyFieldType.NUMBER)
               .conditional(RolloutStrategyAttributeConditional.GREATER_EQUALS)
               .fieldName("age")
               .values([20])
            ]
          )
          ])
    and: "we have a feature repository with this in it"
        repo.updateFeatures([f])
    when: "we create a client context matching the strategy"
        def ccFirst = new TestContext(repo, edge).attr("age", "27").platform(StrategyAttributePlatformName.IOS)
        def ccNoMatch = new TestContext(repo, edge).attr("age", "18").platform(StrategyAttributePlatformName.ANDROID)
        def ccSecond = new TestContext(repo, edge).attr("age", "43").platform(StrategyAttributePlatformName.MACOS)
        def ccThird = new TestContext(repo, edge).attr("age", "18").platform(StrategyAttributePlatformName.MACOS)
        def ccEmpty = new TestContext(repo, edge)
    then: "without the context it is true"
        repo.getFeat("feat1").rawJson == "feature"
        repo.getFeat("feat1").string == null
        ccNoMatch.feature("feat1").rawJson == "feature"
        ccNoMatch.feature("feat1").string == null
        ccSecond.feature("feat1").rawJson == "not-mobile"
        ccFirst.feature("feat1").rawJson == "older-than-twenty"
        ccThird.feature("feat1").rawJson == "not-mobile"
        ccEmpty.feature("feat1").rawJson == "feature"
  }
}
