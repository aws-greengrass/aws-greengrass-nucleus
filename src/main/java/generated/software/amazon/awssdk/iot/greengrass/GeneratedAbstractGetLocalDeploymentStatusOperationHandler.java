package generated.software.amazon.awssdk.iot.greengrass;

import java.lang.Override;
import generated.software.amazon.awssdk.iot.greengrass.model.GetLocalDeploymentStatusRequest;
import generated.software.amazon.awssdk.iot.greengrass.model.GetLocalDeploymentStatusResponse;
import software.amazon.eventstream.iot.OperationModelContext;
import software.amazon.eventstream.iot.model.EventStreamJsonMessage;
import software.amazon.eventstream.iot.server.OperationContinuationHandler;
import software.amazon.eventstream.iot.server.OperationContinuationHandlerContext;

public abstract class GeneratedAbstractGetLocalDeploymentStatusOperationHandler extends OperationContinuationHandler<GetLocalDeploymentStatusRequest, GetLocalDeploymentStatusResponse, EventStreamJsonMessage, EventStreamJsonMessage> {
  protected GeneratedAbstractGetLocalDeploymentStatusOperationHandler(
      OperationContinuationHandlerContext context) {
    super(context);
  }

  @Override
  public OperationModelContext<GetLocalDeploymentStatusRequest, GetLocalDeploymentStatusResponse, EventStreamJsonMessage, EventStreamJsonMessage> getOperationModelContext(
      ) {
    return GreengrassCoreIPCServiceModel.getGetLocalDeploymentStatusModelContext();
  }
}
