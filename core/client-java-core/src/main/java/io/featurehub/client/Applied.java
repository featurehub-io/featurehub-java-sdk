package io.featurehub.client;

public class Applied {
  private final boolean matched;
  private final Object value;
  private final String strategyId;

  public Applied(boolean matched, Object value, String strategyId) {
    this.matched = matched;
    this.value = value;
    this.strategyId = strategyId;
  }

  public boolean isMatched() {
    return matched;
  }

  public Object getValue() {
    return value;
  }

  public String getStrategyId() {
    return strategyId;
  }

  @Override
  public String toString() {
    return "Applied{" +
      "matched=" + matched +
      ", value=" + value +
      '}';
  }
}
