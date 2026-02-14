package io.featurehub.client;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * We just want to have our threads named "FeatureHub" so folks can find them.
 */
public class FeatureHubThreadFactory implements ThreadFactory {

  // Note:  The source code for this class was based entirely on
  // Executors.DefaultThreadFactory class from the JDK8 source.
  // The only change made is the ability to configure the thread
  // name prefix.

  private static final AtomicInteger poolNumber = new AtomicInteger(1);
  private final ThreadGroup group;
  private final AtomicInteger threadNumber = new AtomicInteger(1);
  private final String namePrefix;

  public FeatureHubThreadFactory() {
    SecurityManager s = System.getSecurityManager();
    group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
    namePrefix = "featurehub-" + poolNumber.getAndIncrement() + "-thread-";
  }

  @Override
  public Thread newThread(Runnable r) {
    Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
    if (t.isDaemon()) {
      t.setDaemon(false);
    }
    if (t.getPriority() != Thread.NORM_PRIORITY) {
      t.setPriority(Thread.NORM_PRIORITY);
    }
    return t;
  }
}
