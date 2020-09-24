package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.CreateLocalDeploymentRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.CreateLocalDeploymentResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractCreateLocalDeploymentOperationHandler extends OperationContinuationHandler<CreateLocalDeploymentRequest, CreateLocalDeploymentResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractCreateLocalDeploymentOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<CreateLocalDeploymentRequest> getRequestClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.CreateLocalDeploymentRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<CreateLocalDeploymentResponse> getResponseClass() {
    return generated.software.amazon.awssdk.iot.greengrass.model.CreateLocalDeploymentResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.CREATE_LOCAL_DEPLOYMENT;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
