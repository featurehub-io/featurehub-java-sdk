package io.featurehub.client

import spock.lang.Specification
import spock.lang.Unroll

/**
 * featurehub-protocol.yaml encoding.evaluation_context.context_sha.examples - every SDK reproduces these.
 * Java attributes are strings, so the numeric and boolean vector is given as the strings they become.
 */
class ContextShaSpec extends Specification {
  @Unroll
  def "the header and contextSha for #attributes match the spec"() {
    when:
      def built = FeatureStateUtils.generateXFeatureHubHeaderFromMap(attributes)
    then:
      built == header
      FeatureStateUtils.contextSha(built) == contextSha
    where:
      attributes                                           | header                                                                                     | contextSha
      [:]                                                  | null                                                                                       | '0'
      [userkey: ['fred'], country: ['nz']]                 | 'country=nz,userkey=fred'                                                                  | 'bbaf44b81bc349603f38ece814e9b741fea50e58990db3bd24988ee69eec0603'
      [shop: ['a&b', 'c d']]                               | 'shop=a%26b%2Cc%20d'                                                                       | '5f2930219932130037f572be9de4ae5cea89983dba663d9400437a63c1dea22c'
      ['my key': ['x'], 'a,b=c': ['y']]                    | 'a%2Cb%3Dc=y,my%20key=x'                                                                   | 'a9d1b1707b4d38824da99d1daf06a1b9b24f7d1397d0e138153a946f1575d40a'
      [name: ["Mary Jane+O'Neil ~*()!"]]                   | "name=Mary%20Jane%2BO'Neil%20~*()!"                                                        | 'ff5b8c6d2cc3bbebfc3285b9106b3b300082ecfc5db6d1e4e37e685d0dbbe653'
      [city: ['Zürich'], greeting: ['สวัสดี']]               | 'city=Z%C3%BCrich,greeting=%E0%B8%AA%E0%B8%A7%E0%B8%B1%E0%B8%AA%E0%B8%94%E0%B8%B5'          | 'd0f83f10b3e4f06cc9e3d57736c75f91dce1f16ed6035f0b430ccd7f37a50dce'
      ['a!': ['1'], 'a ': ['2']]                           | 'a!=1,a%20=2'                                                                              | '16cab394e891c04ba620debb77279050b6a88e4b58da3bffeb286c4428289e72'
      [userkey: ['fred'], empty: [], gone: [null]]         | 'userkey=fred'                                                                             | '6a1d1fa42d1c1917552a255a940792205cb62cc2efd6613ab5a3f75d0038518b'
      [version: ['3'], beta: ['true'], ratio: ['0.5']]     | 'beta=true,ratio=0.5,version=3'                                                            | 'b8ec246368e079b0f2b84682231065d867c943e964b5d5acfb557c86cb45b0fb'
  }

  def "an empty header has a contextSha of 0, not the hash of the empty string"() {
    expect:
      FeatureStateUtils.contextSha('') == '0'
      FeatureStateUtils.contextSha(null) == '0'
  }

  def "keys and Thai values are encoded as encodeURIComponent does"() {
    expect:
      FeatureStateUtils.generateXFeatureHubHeaderFromMap(
        ['kwong pastry': ['omu&co'], userkey: ['lingling kwong'], name: ['กรณ์นภัส']]) ==
        'kwong%20pastry=omu%26co,name=%E0%B8%81%E0%B8%A3%E0%B8%93%E0%B9%8C%E0%B8%99%E0%B8%A0%E0%B8%B1%E0%B8%AA,userkey=lingling%20kwong'
  }
}
