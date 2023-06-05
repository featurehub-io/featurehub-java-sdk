package io.featurehub.client

import io.featurehub.sse.model.StrategyAttributeCountryName
import io.featurehub.sse.model.StrategyAttributeDeviceName
import io.featurehub.sse.model.StrategyAttributePlatformName
import spock.lang.Specification

class BaseClientContextSpec extends Specification {
  InternalFeatureRepository repo
  EdgeService edgeService
  BaseClientContext ctx

  def setup() {
    edgeService = Mock(EdgeService)
    repo = Mock(InternalFeatureRepository)
    ctx = new BaseClientContext(repo, edgeService)
  }

  def "the client context encodes as expected"() {
    when: "i encode the context"
      def tc = ctx.userKey("DJElif")
        .country(StrategyAttributeCountryName.TURKEY)
        .attr("city", "Istanbul")
        .attrs("musical styles", Arrays.asList("psychedelic", "deep"))
        .device(StrategyAttributeDeviceName.DESKTOP)
        .platform(StrategyAttributePlatformName.ANDROID)
        .version("2.3.7")
        .sessionKey("anjunadeep").build().get()

    and: "i do the same thing again to ensure i can reset everything"
      tc.userKey("DJElif")
        .country(StrategyAttributeCountryName.TURKEY)
        .attr("city", "Istanbul")
        .attrs("musical styles", Arrays.asList("psychedelic", "deep"))
        .device(StrategyAttributeDeviceName.DESKTOP)
        .platform(StrategyAttributePlatformName.ANDROID)
        .version("2.3.7")
        .sessionKey("anjunadeep").build().get()
    then:
      FeatureStateUtils.generateXFeatureHubHeaderFromMap(tc.context()) ==
        'city=Istanbul,country=turkey,device=desktop,musical styles=psychedelic%2Cdeep,platform=android,session=anjunadeep,userkey=DJElif,version=2.3.7'
  }
}
