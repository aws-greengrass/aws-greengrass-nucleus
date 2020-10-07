package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;

import generated.software.amazon.awssdk.iot.greengrass.model.IoTCoreMessage;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToIoTCoreRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.SubscribeToIoTCoreResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractSubscribeToIoTCoreOperationHandler extends OperationContinuationHandler<SubscribeToIoTCoreRequest, SubscribeToIoTCoreResponse, EventStreamableJsonMessage, IoTCoreMessage> {
  protected GeneratedAbstractSubscribeToIoTCoreOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<SubscribeToIoTCoreRequest> getRequestClass() {
    return SubscribeToIoTCoreRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<SubscribeToIoTCoreResponse> getResponseClass() {
    return SubscribeToIoTCoreResponse.class;
  }

  @Override
  protected final Class<IoTCoreMessage> getStreamingResponseClass() {
    return IoTCoreMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.SUBSCRIBE_TO_IOT_CORE;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return true;
  }
}
