package io.featurehub.strategies.percentage

import spock.lang.Specification

class PercentageMurmurCalculatorSpec extends Specification {
  def "each instance of the calculator generates consistent values"() {
    when: "i have a base value"
      def base = new PercentageMumurCalculator().determineClientPercentage("irina", "one")
    then:
      for(int i = 0; i < 10; i ++) {
        new PercentageMumurCalculator().determineClientPercentage("irina", "one") == base
      }
  }

  def "different keys give different values"() {
    when: "i have a calculator"
      def calc = new PercentageMumurCalculator()
    then:
      calc.determineClientPercentage("irina", "one") != calc.determineClientPercentage("irina", "two")
      calc.determineClientPercentage("seb", "one") != calc.determineClientPercentage("irina", "one")
  }
}
