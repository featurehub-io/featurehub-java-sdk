package io.featurehub.client;

/**
 * We are using this instead of Supplier because Android 21 does not support Supplier.
 * @param <T>
 */
public interface ObjectSupplier<T> {
  /**
   * Gets a result.
   *
   * @return a result
   */
  T get();
}
