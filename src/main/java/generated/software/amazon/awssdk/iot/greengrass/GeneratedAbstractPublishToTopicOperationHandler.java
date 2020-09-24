package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.PublishToTopicRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.PublishToTopicResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractPublishToTopicOperationHandler extends OperationContinuationHandler<PublishToTopicRequest, PublishToTopicResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractPublishToTopicOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<PublishToTopicRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.PublishToTopicRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<PublishToTopicResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.PublishToTopicResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.PUBLISH_TO_TOPIC;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
