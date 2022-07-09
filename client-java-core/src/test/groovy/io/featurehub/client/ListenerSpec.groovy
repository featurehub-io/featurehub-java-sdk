package io.featurehub.client

import io.featurehub.sse.model.FeatureValueType
import io.featurehub.sse.model.RolloutStrategyAttributeConditional
import io.featurehub.sse.model.RolloutStrategyFieldType
import io.featurehub.sse.model.FeatureRolloutStrategy
import io.featurehub.sse.model.FeatureRolloutStrategyAttribute
import spock.lang.Specification

import static io.featurehub.client.BaseClientContext.USER_KEY

class ListenerSpec extends Specification {
  def "When a listener fires, it will always attempt to take the context into account if it was listened to via a context"() {
    given: "i have a setup"
        def fStore = Mock(FeatureStore)
        fStore.getFeatureValueInterceptors() >> []
        def ctx = Mock(ClientContext)
        def key = "fred"
    and: "a feature"
        def feat = new FeatureStateBase(fStore, key)
        def ctxFeat = feat.withContext(ctx)
        BigDecimal n1;
    BigDecimal n2;
        feat.addListener({ fs ->
          n1 = fs.number
        })
        ctxFeat.addListener({ fs -> n2 = fs.number })
    when: "i set the feature state"
        feat.setFeatureState(new io.featurehub.sse.model.FeatureState().id(UUID.randomUUID()).key(key).l(false).value(16).type(FeatureValueType.NUMBER).addStrategiesItem(new FeatureRolloutStrategy().value(12).addAttributesItem(
          new FeatureRolloutStrategyAttribute().conditional(RolloutStrategyAttributeConditional.EQUALS).type(RolloutStrategyFieldType.STRING).fieldName(USER_KEY).addValuesItem("fred")
        )))
    then:
        n1 == 16
        n2 == 12
        2 *  fStore.execute({Runnable cmd ->
          cmd.run()
        })
        1 * fStore.applyFeature(_, key, _, ctx) >> new Applied(true, 12)
  }
}
