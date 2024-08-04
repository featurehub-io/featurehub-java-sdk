package io.featurehub.sdk.usageadapter.segment;

import com.segment.analytics.MessageTransformer;
import com.segment.analytics.messages.Message;
import com.segment.analytics.messages.MessageBuilder;
import io.featurehub.client.ClientContext;
import io.featurehub.client.usage.UsageFeaturesCollectionContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * SegmentMessageTransformer is designed to allow an analytics builder to attach the current user's features
 * and context information (if any). Segment's MessageBuilder has no way of getting the current context, so being
 * able to add information with multiple message transformers isn't possible.
 * <p>
 * Issue: https://github.com/segmentio/analytics-java/issues/486
 */
public class SegmentMessageTransformer implements MessageTransformer {
  private final List<Message.Type> augmentTypes;
  private final Supplier<@Nullable ClientContext> contextSource;
  private final boolean useAnonymousUser;
  private final boolean setUserOnMessage;

  /**
   * Creates a new Segment message transformer that augments all outgoing messages of the specified type with
   * the current user's feature values.
   *
   * @param augmentTypes     - what types of message to augment
   * @param contextSource    - how to get the current user's context, likely  to involve ThreadLocalStorage
   * @param useAnonymousUser - always use an anonymous user to prevent user-tracking burn through
   * @param setUserOnMessage - should we even set the user in case something else is doing that.
   */
  public SegmentMessageTransformer(Message.Type[] augmentTypes,
                                   Supplier<@Nullable ClientContext> contextSource,
                                   boolean useAnonymousUser, boolean setUserOnMessage) {
    this.augmentTypes = List.of(augmentTypes);
    this.contextSource = contextSource;
    this.useAnonymousUser = useAnonymousUser;
    this.setUserOnMessage = setUserOnMessage;
  }

  @Override
  public boolean transform(MessageBuilder builder) {
    final ClientContext context = contextSource.get();

    if (context != null && augmentTypes.contains(builder.type())) {
      // create a holder that will collect the user and all the respective data
      final UsageFeaturesCollectionContext usage = new UsageFeaturesCollectionContext();

      context.fillUsageCollection(usage);

      augmentUser(builder, usage);

      builder.context(usage.toMap());
    }

    return true;
  }

  private void augmentUser(MessageBuilder<?, ?> builder, UsageFeaturesCollectionContext usage) {
    if (setUserOnMessage) {
      if (useAnonymousUser) {
        builder.userId("anonymous");
      } else {
        builder.userId(usage.getUserKey());
      }
    }
  }
}
