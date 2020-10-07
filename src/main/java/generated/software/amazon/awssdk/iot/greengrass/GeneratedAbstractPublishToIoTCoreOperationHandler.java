package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;

import generated.software.amazon.awssdk.iot.greengrass.model.PublishToIoTCoreRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.PublishToIoTCoreResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractPublishToIoTCoreOperationHandler extends OperationContinuationHandler<PublishToIoTCoreRequest, PublishToIoTCoreResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractPublishToIoTCoreOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<PublishToIoTCoreRequest> getRequestClass() {
    return PublishToIoTCoreRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<PublishToIoTCoreResponse> getResponseClass() {
    return PublishToIoTCoreResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.PUBLISH_TO_IOT_CORE;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
