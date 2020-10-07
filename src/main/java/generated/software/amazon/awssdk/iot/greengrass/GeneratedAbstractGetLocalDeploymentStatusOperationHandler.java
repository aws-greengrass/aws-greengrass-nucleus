package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import generated.software.amazon.awssdk.iot.greengrass.model.GetLocalDeploymentStatusRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetLocalDeploymentStatusResponse;
import software.amazon.eventstream.iot.EventStreamableJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractGetLocalDeploymentStatusOperationHandler extends OperationContinuationHandler<GetLocalDeploymentStatusRequest, GetLocalDeploymentStatusResponse, EventStreamableJsonMessage, EventStreamableJsonMessage> {
  protected GeneratedAbstractGetLocalDeploymentStatusOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  protected final Class<GetLocalDeploymentStatusRequest> getRequestClass() {
    return GetLocalDeploymentStatusRequest.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingRequestClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final Class<GetLocalDeploymentStatusResponse> getResponseClass() {
    return GetLocalDeploymentStatusResponse.class;
  }

  @Override
  protected final Class<EventStreamableJsonMessage> getStreamingResponseClass() {
    return software.amazon.eventstream.iot.EventStreamableJsonMessage.class;
  }

  @Override
  protected final String getOperationName() {
    return GreengrassCoreIPCService.GET_LOCAL_DEPLOYMENT_STATUS;
  }

  @Override
  protected final boolean isStreamingOperation() {
    return false;
  }
}
