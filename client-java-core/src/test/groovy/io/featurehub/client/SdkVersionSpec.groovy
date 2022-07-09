package io.featurehub.client

import io.featurehub.client.utils.SdkVersion
import spock.lang.Specification

class SdkVersionSpec extends Specification {
  def "sdk version returns expected version"() {
    expect:
      SdkVersion.sdkVersion() == "3.7.5"
  }
}
