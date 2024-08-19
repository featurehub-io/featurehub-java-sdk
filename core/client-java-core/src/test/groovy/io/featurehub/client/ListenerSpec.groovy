package io.featurehub.client

import io.featurehub.sse.model.FeatureValueType
import io.featurehub.sse.model.RolloutStrategyAttributeConditional
import io.featurehub.sse.model.RolloutStrategyFieldType
import io.featurehub.sse.model.FeatureRolloutStrategy
import io.featurehub.sse.model.FeatureRolloutStrategyAttribute
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

import static io.featurehub.client.BaseClientContext.USER_KEY

class ListenerSpec extends Specification {
  def "When a listener fires, it will always attempt to take the context into account if it was listened to via a context"() {
    given: "i have a setup"
        def repo = Mock(InternalFeatureRepository)
        def edge = Mock(EdgeService)
//        def ctx = Mock(InternalContext)
        def ctx = new BaseClientContext(repo, edge)
        def key = "fred"
        def id = UUID.randomUUID()
    and: "a feature"
        def feat = new FeatureStateBase(repo, key)
        def ctxFeat = feat.withContext(ctx)
        BigDecimal n1;
        BigDecimal n2;
        // the listeners will trigger a repo.execute when they are evaluated
        feat.addListener({ fs ->
          n1 = fs.number
        })
        ctxFeat.addListener({ fs -> n2 = fs.number })
    when: "i set the feature state"
        feat.setFeatureState(new io.featurehub.sse.model.FeatureState().id(id).key(key).l(false)
          .value(16).type(FeatureValueType.NUMBER).addStrategiesItem(new FeatureRolloutStrategy().value(12).addAttributesItem(
          new FeatureRolloutStrategyAttribute().conditional(RolloutStrategyAttributeConditional.EQUALS)
            .type(RolloutStrategyFieldType.STRING).fieldName(USER_KEY).addValuesItem("fred")
        )))
    then:
        n1 == 16
        n2 == 12
        2 * repo.findIntercept(false, key) >> null  // one for each listener
        3 * repo.execute({Runnable cmd ->  // 2 for listeners, 1 for firing the "used" on the repo via the context
          cmd.run()
        })
        1 * repo.applyFeature(_, key, _, ctx) >> new Applied(true, 12)
//        1 * ctx.used(key, id, 12, FeatureValueType.NUMBER)
        1 * repo.used(key, id, FeatureValueType.NUMBER, 16, null, null)
        1 * repo.used(key, id, FeatureValueType.NUMBER, 12, {}, null)
        1 * edge.poll() >> CompletableFuture.completedFuture(Readiness.Ready)
        0 * _
  }
}
