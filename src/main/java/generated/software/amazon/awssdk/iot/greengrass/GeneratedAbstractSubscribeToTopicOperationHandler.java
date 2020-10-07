package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;

import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToTopicRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToTopicResponse;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscriptionResponseMessage;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSubscribeToTopicOperationHandler extends OperationContinuationHandler<SubscribeToTopicRequest, SubscribeToTopicResponse, EventStreamableJsonMessage, SubscriptionResponseMessage> {
  protected GeneratedAbstractSubscribeToTopicOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<SubscribeToTopicRequest> getRequestClass() {
    return SubscribeToTopicRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<SubscribeToTopicResponse> getResponseClass() {
    return SubscribeToTopicResponse.class;
  }

  @Override
  protected final Class<SubscriptionResponseMessage> getStreamingResponseClass() {
    return SubscriptionResponseMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.SUBSCRIBE_TO_TOPIC;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return true;
  }
}
